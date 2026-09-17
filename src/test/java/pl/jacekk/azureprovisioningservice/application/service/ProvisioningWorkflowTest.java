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
import pl.jacekk.azureprovisioningservice.domain.port.out.ProvisionedSubscription;
import pl.jacekk.azureprovisioningservice.domain.port.out.ReconcileOutcome;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class ProvisioningWorkflowTest {

    private static final Instant NOW = Instant.parse("2026-09-11T10:00:00Z");
    private static final String ALIAS = "acct-acc-1";
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
        when(subscriptions.ensureSubscription(any(), any()))
                .thenReturn(new ProvisionedSubscription("sub-1", ReconcileOutcome.CREATED));
        when(subscriptions.ensureTags(any(), any())).thenReturn(ReconcileOutcome.CREATED);
        when(managementGroups.ensurePlacedUnder(any(), any())).thenReturn(ReconcileOutcome.CREATED);
    }

    private Account pending() {
        Account account = Account.newRequest("acc-1", "team-alpha-prod", "mg-workloads", LABELS,
                new JobLease("job-1", "replica-a", NOW.plusSeconds(300)), NOW);
        when(accounts.findById("acc-1")).thenReturn(Optional.of(account));
        return account;
    }

    /** A failed run that has been claimed as a retry, so it is PENDING again. */
    private Account retriedAfterFailure(String subscriptionRecordedByTheFailedRun) {
        Account account = pending();
        account.startProvisioning(NOW);
        if (subscriptionRecordedByTheFailedRun != null) {
            account.recordSubscription(subscriptionRecordedByTheFailedRun, NOW);
        }
        account.failStep("boom", NOW);
        account.startRetry(new JobLease("job-2", "replica-a", NOW.plusSeconds(300)), NOW);
        return account;
    }

    @Test
    void walksEveryStepAndCompletes() {
        Account account = pending();

        workflow.run("acc-1");

        InOrder order = inOrder(subscriptions, managementGroups);
        order.verify(subscriptions).ensureSubscription(ALIAS, "team-alpha-prod");
        order.verify(managementGroups).ensurePlacedUnder("sub-1", "mg-workloads");
        order.verify(subscriptions).ensureTags("sub-1", LABELS);

        assertThat(account.getStatus()).isEqualTo(ProvisioningStatus.COMPLETED);
        assertThat(account.getAzureSubscriptionId()).isEqualTo("sub-1");
        assertThat(account.getErrorDetail()).isNull();
    }

    @Test
    void recordsEachStatusOnItsWayThroughTheSteps() {
        Account account = pending();
        AtomicReference<ProvisioningStatus> duringCreate = new AtomicReference<>();
        AtomicReference<ProvisioningStatus> duringPlacement = new AtomicReference<>();
        AtomicReference<ProvisioningStatus> duringTagging = new AtomicReference<>();
        when(subscriptions.ensureSubscription(any(), any())).thenAnswer(invocation -> {
            duringCreate.set(account.getStatus());
            return new ProvisionedSubscription("sub-1", ReconcileOutcome.CREATED);
        });
        when(managementGroups.ensurePlacedUnder(any(), any())).thenAnswer(invocation -> {
            duringPlacement.set(account.getStatus());
            return ReconcileOutcome.CREATED;
        });
        when(subscriptions.ensureTags(any(), any())).thenAnswer(invocation -> {
            duringTagging.set(account.getStatus());
            return ReconcileOutcome.CREATED;
        });

        workflow.run("acc-1");

        assertThat(duringCreate.get()).isEqualTo(ProvisioningStatus.CREATING_SUBSCRIPTION);
        assertThat(duringPlacement.get()).isEqualTo(ProvisioningStatus.ASSIGNING_MANAGEMENT_GROUP);
        assertThat(duringTagging.get()).isEqualTo(ProvisioningStatus.APPLYING_LABELS);
    }

    @Test
    void aRerunAdoptsTheSubscriptionTheFailedRunBuilt() {
        Account account = retriedAfterFailure("sub-1");
        when(subscriptions.ensureSubscription(ALIAS, "team-alpha-prod"))
                .thenReturn(new ProvisionedSubscription("sub-1", ReconcileOutcome.ADOPTED));

        workflow.run("acc-1");

        assertThat(account.getAzureSubscriptionId())
                .as("the same subscription, not a replacement")
                .isEqualTo("sub-1");
        assertThat(account.getStatus()).isEqualTo(ProvisioningStatus.COMPLETED);
    }

    @Test
    void aRerunAdoptsASubscriptionTheFailedRunNeverManagedToRecord() {
        // The run that created it died before the id reached Mongo. The alias outlives that.
        Account account = retriedAfterFailure(null);
        when(subscriptions.ensureSubscription(ALIAS, "team-alpha-prod"))
                .thenReturn(new ProvisionedSubscription("sub-untracked", ReconcileOutcome.ADOPTED));

        workflow.run("acc-1");

        assertThat(account.getAzureSubscriptionId()).isEqualTo("sub-untracked");
        assertThat(account.getStatus()).isEqualTo(ProvisioningStatus.COMPLETED);
    }

    @Test
    void aRerunOverAlreadyCorrectStateChangesNothing() {
        Account account = retriedAfterFailure("sub-1");
        when(subscriptions.ensureSubscription(any(), any()))
                .thenReturn(new ProvisionedSubscription("sub-1", ReconcileOutcome.ADOPTED));
        when(managementGroups.ensurePlacedUnder(any(), any())).thenReturn(ReconcileOutcome.ALREADY_SATISFIED);
        when(subscriptions.ensureTags(any(), any())).thenReturn(ReconcileOutcome.ALREADY_SATISFIED);

        workflow.run("acc-1");

        assertThat(account.getStatus()).isEqualTo(ProvisioningStatus.COMPLETED);
        assertThat(account.getAzureSubscriptionId()).isEqualTo("sub-1");
    }

    @Test
    void failedSubscriptionCreationNamesTheStep() {
        Account account = pending();
        when(subscriptions.ensureSubscription(any(), any()))
                .thenThrow(new AzureProvisioningException("quota exceeded"));

        workflow.run("acc-1");

        assertThat(account.getStatus()).isEqualTo(ProvisioningStatus.FAILED);
        assertThat(account.getErrorDetail()).isEqualTo("Step CREATING_SUBSCRIPTION failed: quota exceeded");
        verifyNoInteractions(managementGroups);
    }

    @Test
    void failedPlacementNamesTheStepAndKeepsTheSubscription() {
        Account account = pending();
        when(managementGroups.ensurePlacedUnder(any(), any()))
                .thenThrow(new AzureProvisioningException("management group not found"));

        workflow.run("acc-1");

        assertThat(account.getStatus()).isEqualTo(ProvisioningStatus.FAILED);
        assertThat(account.getErrorDetail())
                .isEqualTo("Step ASSIGNING_MANAGEMENT_GROUP failed: management group not found");
        assertThat(account.getAzureSubscriptionId())
                .as("kept, so the next run adopts it rather than building another")
                .isEqualTo("sub-1");
    }

    @Test
    void failedTaggingNamesTheStep() {
        Account account = pending();
        when(subscriptions.ensureTags(any(), any()))
                .thenThrow(new AzureProvisioningException("tag quota exceeded"));

        workflow.run("acc-1");

        assertThat(account.getStatus()).isEqualTo(ProvisioningStatus.FAILED);
        assertThat(account.getErrorDetail()).isEqualTo("Step APPLYING_LABELS failed: tag quota exceeded");
    }

    @Test
    void givesUpQuietlyWhenAnotherReplicaTookTheJobOver() {
        pending();
        when(accounts.save(any())).thenThrow(new ConcurrentAccountModificationException("acc-1"));

        assertThatCode(() -> workflow.run("acc-1")).doesNotThrowAnyException();

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

        assertThatCode(() -> workflow.run("acc-1")).doesNotThrowAnyException();

        assertThat(account.getStatus()).isNotEqualTo(ProvisioningStatus.COMPLETED);
    }

    @Test
    void renewsTheLeaseAsItCrossesSteps() {
        Account account = pending();

        workflow.run("acc-1");

        assertThat(account.getLeaseExpiresAt()).isEqualTo(NOW.plus(Duration.ofMinutes(5)));
    }

    @Test
    void carriesTheJobIdInTheLoggingContextAndClearsItAfterwards() {
        pending();
        AtomicReference<String> jobIdDuringRun = new AtomicReference<>();
        when(subscriptions.ensureSubscription(any(), any())).thenAnswer(invocation -> {
            jobIdDuringRun.set(MDC.get("jobId"));
            return new ProvisionedSubscription("sub-1", ReconcileOutcome.CREATED);
        });

        workflow.run("acc-1");

        assertThat(jobIdDuringRun.get()).isEqualTo("job-1");
        assertThat(MDC.get("jobId")).isNull();
    }

    @Test
    void ignoresAnAccountThatNoLongerExists() {
        when(accounts.findById("gone")).thenReturn(Optional.empty());

        workflow.run("gone");

        verifyNoInteractions(subscriptions);
        verifyNoInteractions(managementGroups);
    }
}
