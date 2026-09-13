package pl.jacekk.azureprovisioningservice.application.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.slf4j.MDC;
import pl.jacekk.azureprovisioningservice.config.ProvisioningProperties;
import pl.jacekk.azureprovisioningservice.domain.model.Account;
import pl.jacekk.azureprovisioningservice.domain.model.JobLease;
import pl.jacekk.azureprovisioningservice.domain.model.Labels;
import pl.jacekk.azureprovisioningservice.domain.model.ProvisioningStatus;
import pl.jacekk.azureprovisioningservice.domain.port.out.AccountRepositoryPort;
import pl.jacekk.azureprovisioningservice.domain.port.out.AzureProvisioningException;
import pl.jacekk.azureprovisioningservice.domain.port.out.AzureSubscriptionPort;
import pl.jacekk.azureprovisioningservice.domain.port.out.ConcurrentAccountModificationException;
import pl.jacekk.azureprovisioningservice.domain.port.out.ManagementGroupPort;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.Optional;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class ProvisioningWorkflowTest {

    private static final Instant NOW = Instant.parse("2026-09-11T10:00:00Z");
    private static final Labels LABELS = Labels.of(Map.of(
            Labels.COST_CENTER_ID, "CC-1001",
            Labels.COST_CENTER_ID_PROVIDER, "sap",
            Labels.PROJECT_INTERNAL_ID, "PRJ-42",
            Labels.PROJECT_INTERNAL_ID_PROVIDER, "servicenow"));

    private final AccountRepositoryPort accounts = mock(AccountRepositoryPort.class);
    private final AzureSubscriptionPort subscriptions = mock(AzureSubscriptionPort.class);
    private final ManagementGroupPort managementGroups = mock(ManagementGroupPort.class);
    private final ProvisioningProperties properties =
            new ProvisioningProperties(Duration.ofMinutes(5), Duration.ofMinutes(1), 100, "replica-a");

    private AsyncProvisioningWorkflow workflow;

    @BeforeEach
    void setUp() {
        workflow = new AsyncProvisioningWorkflow(accounts, subscriptions, managementGroups,
                Clock.fixed(NOW, ZoneOffset.UTC), properties);
        when(accounts.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        when(subscriptions.createSubscription(any(), any())).thenReturn("sub-new");
    }

    private Account pending() {
        Account account = Account.newRequest("acc-1", "team-alpha-prod", "mg-workloads", LABELS,
                new JobLease("job-1", "replica-a", NOW.plusSeconds(300)), NOW);
        when(accounts.findById("acc-1")).thenReturn(Optional.of(account));
        return account;
    }

    /** A failed run that got as far as creating the subscription, then claimed as a retry. */
    private Account claimedRetryWithOrphan() {
        Account account = Account.newRequest("acc-1", "team-alpha-prod", "mg-workloads", LABELS,
                new JobLease("job-1", "replica-a", NOW.plusSeconds(300)), NOW);
        account.startProvisioning(NOW);
        account.recordSubscriptionCreated("sub-orphan", NOW);
        account.startAssigningManagementGroup(NOW);
        account.failStep("management group not found", NOW);
        account.startCleanup(new JobLease("job-2", "replica-a", NOW.plusSeconds(300)), NOW);
        when(accounts.findById("acc-1")).thenReturn(Optional.of(account));
        return account;
    }

    /** A run that reached Azure but died before it could record what Azure gave it. */
    private Account claimedRetryWithUntrackedOrphan() {
        Account account = Account.newRequest("acc-1", "team-alpha-prod", "mg-workloads", LABELS,
                new JobLease("job-1", "replica-a", NOW.plusSeconds(300)), NOW);
        account.startProvisioning(NOW);
        account.failStep("worker died", NOW);
        account.startCleanup(new JobLease("job-2", "replica-a", NOW.plusSeconds(300)), NOW);
        when(accounts.findById("acc-1")).thenReturn(Optional.of(account));
        return account;
    }

    @Test
    void createsTheSubscriptionUnderTheAttemptsOwnAlias() {
        pending();

        workflow.runFresh("acc-1");

        verify(subscriptions).createSubscription("acct-acc-1-1", "team-alpha-prod");
    }

    @Test
    void persistsTheAttemptBeforeAskingAzureToCreateAnything() {
        pending();
        List<Integer> persistedAttempts = new ArrayList<>();
        AtomicReference<Integer> durableAtCreateTime = new AtomicReference<>();
        when(accounts.save(any())).thenAnswer(invocation -> {
            persistedAttempts.add(((Account) invocation.getArgument(0)).getAttempt());
            return invocation.getArgument(0);
        });
        when(subscriptions.createSubscription(any(), any())).thenAnswer(invocation -> {
            durableAtCreateTime.set(persistedAttempts.isEmpty() ? null
                    : persistedAttempts.get(persistedAttempts.size() - 1));
            return "sub-new";
        });

        workflow.runFresh("acc-1");

        assertThat(durableAtCreateTime.get())
                .as("the attempt must already be durable, or a crash here leaks the subscription")
                .isEqualTo(1);
    }

    @Test
    void deletesASubscriptionThePreviousAttemptCreatedButNeverRecorded() {
        Account account = claimedRetryWithUntrackedOrphan();
        when(subscriptions.findSubscriptionIdByAlias("acct-acc-1-1"))
                .thenReturn(Optional.of("sub-untracked"));

        workflow.runRetry("acc-1");

        verify(subscriptions).deleteSubscription("sub-untracked");
        verify(subscriptions).createSubscription("acct-acc-1-2", "team-alpha-prod");
        assertThat(account.getStatus()).isEqualTo(ProvisioningStatus.COMPLETED);
    }

    @Test
    void deletesNothingWhenThePreviousAttemptNeverReachedAzure() {
        Account account = claimedRetryWithUntrackedOrphan();
        when(subscriptions.findSubscriptionIdByAlias(any())).thenReturn(Optional.empty());

        workflow.runRetry("acc-1");

        verify(subscriptions, never()).deleteSubscription(any());
        assertThat(account.getStatus()).isEqualTo(ProvisioningStatus.COMPLETED);
    }

    @Test
    void trustsTheRecordedSubscriptionIdRatherThanLookingUpTheAlias() {
        claimedRetryWithOrphan();

        workflow.runRetry("acc-1");

        verify(subscriptions).deleteSubscription("sub-orphan");
        verify(subscriptions, never()).findSubscriptionIdByAlias(any());
    }

    @Test
    void freshRunWalksEveryStepAndCompletes() {
        Account account = pending();

        workflow.runFresh("acc-1");

        InOrder order = inOrder(subscriptions, managementGroups);
        order.verify(subscriptions).createSubscription(any(), eq("team-alpha-prod"));
        order.verify(managementGroups).assignSubscription("sub-new", "mg-workloads");
        order.verify(subscriptions).applyTags("sub-new", LABELS);

        assertThat(account.getStatus()).isEqualTo(ProvisioningStatus.COMPLETED);
        assertThat(account.getAzureSubscriptionId()).isEqualTo("sub-new");
        assertThat(account.getErrorDetail()).isNull();
    }

    @Test
    void recordsEachStatusOnItsWayThroughTheSteps() {
        Account account = pending();
        AtomicReference<ProvisioningStatus> statusDuringCreate = new AtomicReference<>();
        AtomicReference<ProvisioningStatus> statusDuringAssign = new AtomicReference<>();
        AtomicReference<ProvisioningStatus> statusDuringTags = new AtomicReference<>();
        when(subscriptions.createSubscription(any(), any())).thenAnswer(invocation -> {
            statusDuringCreate.set(account.getStatus());
            return "sub-new";
        });
        doAnswer(invocation -> {
            statusDuringAssign.set(account.getStatus());
            return null;
        }).when(managementGroups).assignSubscription(any(), any());
        doAnswer(invocation -> {
            statusDuringTags.set(account.getStatus());
            return null;
        }).when(subscriptions).applyTags(any(), any());

        workflow.runFresh("acc-1");

        assertThat(statusDuringCreate.get()).isEqualTo(ProvisioningStatus.CREATING_SUBSCRIPTION);
        assertThat(statusDuringAssign.get()).isEqualTo(ProvisioningStatus.ASSIGNING_MANAGEMENT_GROUP);
        assertThat(statusDuringTags.get()).isEqualTo(ProvisioningStatus.APPLYING_LABELS);
    }

    @Test
    void failedSubscriptionCreationNamesTheStep() {
        Account account = pending();
        when(subscriptions.createSubscription(any(), any()))
                .thenThrow(new AzureProvisioningException("quota exceeded"));

        workflow.runFresh("acc-1");

        assertThat(account.getStatus()).isEqualTo(ProvisioningStatus.FAILED);
        assertThat(account.getErrorDetail()).isEqualTo("Step CREATING_SUBSCRIPTION failed: quota exceeded");
        assertThat(account.getAzureSubscriptionId()).isNull();
        verifyNoInteractions(managementGroups);
    }

    @Test
    void failedManagementGroupAssignmentLeavesTheSubscriptionForTheNextRetry() {
        Account account = pending();
        doThrow(new AzureProvisioningException("management group not found"))
                .when(managementGroups).assignSubscription(any(), any());

        workflow.runFresh("acc-1");

        assertThat(account.getStatus()).isEqualTo(ProvisioningStatus.FAILED);
        assertThat(account.getErrorDetail())
                .isEqualTo("Step ASSIGNING_MANAGEMENT_GROUP failed: management group not found");
        assertThat(account.getAzureSubscriptionId())
                .as("cleanup happens on the next retry, not immediately")
                .isEqualTo("sub-new");
        verify(subscriptions, never()).deleteSubscription(any());
    }

    @Test
    void failedLabelApplicationNamesTheStep() {
        Account account = pending();
        doThrow(new AzureProvisioningException("tag quota exceeded"))
                .when(subscriptions).applyTags(any(), any());

        workflow.runFresh("acc-1");

        assertThat(account.getStatus()).isEqualTo(ProvisioningStatus.FAILED);
        assertThat(account.getErrorDetail()).isEqualTo("Step APPLYING_LABELS failed: tag quota exceeded");
    }

    @Test
    void retryDeletesTheOrphanedSubscriptionThenRerunsFromStepOne() {
        Account account = claimedRetryWithOrphan();

        workflow.runRetry("acc-1");

        InOrder order = inOrder(subscriptions, managementGroups);
        order.verify(subscriptions).deleteSubscription("sub-orphan");
        order.verify(subscriptions).createSubscription(any(), eq("team-alpha-prod"));
        order.verify(managementGroups).assignSubscription("sub-new", "mg-workloads");
        order.verify(subscriptions).applyTags("sub-new", LABELS);

        assertThat(account.getStatus()).isEqualTo(ProvisioningStatus.COMPLETED);
        assertThat(account.getAzureSubscriptionId()).isEqualTo("sub-new");
    }

    @Test
    void retryWithNothingToCleanUpStillRerunsEveryStep() {
        Account account = claimedRetryWithOrphan();
        account.markCleanedUp(NOW);

        workflow.runRetry("acc-1");

        verify(subscriptions, never()).deleteSubscription(any());
        verify(subscriptions).createSubscription(any(), eq("team-alpha-prod"));
        verify(managementGroups).assignSubscription("sub-new", "mg-workloads");
        verify(subscriptions).applyTags("sub-new", LABELS);
        assertThat(account.getStatus()).isEqualTo(ProvisioningStatus.COMPLETED);
    }

    @Test
    void cleanupFailureStopsTheRunBeforeAnyProvisioning() {
        Account account = claimedRetryWithOrphan();
        doThrow(new AzureProvisioningException("delete rejected by Azure"))
                .when(subscriptions).deleteSubscription(any());

        workflow.runRetry("acc-1");

        assertThat(account.getStatus()).isEqualTo(ProvisioningStatus.FAILED);
        assertThat(account.getErrorDetail()).isEqualTo("CLEANUP_FAILED: delete rejected by Azure");
        assertThat(account.getAzureSubscriptionId())
                .as("still there, so the next retry tries to clean it up again")
                .isEqualTo("sub-orphan");
        verify(subscriptions, never()).createSubscription(any(), any());
        verifyNoInteractions(managementGroups);
    }

    @Test
    void renewsTheLeaseAsItCrossesSteps() {
        Account account = pending();

        workflow.runFresh("acc-1");

        assertThat(account.getLeaseExpiresAt()).isEqualTo(NOW.plus(Duration.ofMinutes(5)));
    }

    @Test
    void carriesTheJobIdInTheLoggingContextAndClearsItAfterwards() {
        pending();
        AtomicReference<String> jobIdDuringRun = new AtomicReference<>();
        when(subscriptions.createSubscription(any(), any())).thenAnswer(invocation -> {
            jobIdDuringRun.set(MDC.get("jobId"));
            return "sub-new";
        });

        workflow.runFresh("acc-1");

        assertThat(jobIdDuringRun.get()).isEqualTo("job-1");
        assertThat(MDC.get("jobId")).isNull();
    }

    @Test
    void givesUpQuietlyWhenAnotherReplicaTookTheJobOver() {
        // Losing the race is the correct outcome, not an error: the other replica owns this job
        // now. What must not happen is an exception escaping onto the async worker thread.
        pending();
        when(accounts.save(any())).thenThrow(new ConcurrentAccountModificationException("acc-1"));

        assertThatCode(() -> workflow.runFresh("acc-1")).doesNotThrowAnyException();

        verifyNoInteractions(subscriptions);
        verifyNoInteractions(managementGroups);
    }

    @Test
    void givesUpQuietlyWhenTheJobIsTakenOverPartWayThroughTheRun() {
        Account account = pending();
        when(accounts.save(any()))
                .thenAnswer(invocation -> invocation.getArgument(0))
                .thenAnswer(invocation -> invocation.getArgument(0))
                .thenThrow(new ConcurrentAccountModificationException("acc-1"));

        assertThatCode(() -> workflow.runFresh("acc-1")).doesNotThrowAnyException();

        assertThat(account.getStatus()).isNotEqualTo(ProvisioningStatus.COMPLETED);
    }

    @Test
    void ignoresAnAccountThatNoLongerExists() {
        when(accounts.findById("gone")).thenReturn(Optional.empty());

        workflow.runFresh("gone");

        verifyNoInteractions(subscriptions);
        verifyNoInteractions(managementGroups);
    }
}
