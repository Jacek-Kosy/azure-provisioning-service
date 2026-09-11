package pl.jacekk.azureprovisioningservice.application.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import pl.jacekk.azureprovisioningservice.config.ProvisioningProperties;
import pl.jacekk.azureprovisioningservice.domain.model.Account;
import pl.jacekk.azureprovisioningservice.domain.model.AccountNotFoundException;
import pl.jacekk.azureprovisioningservice.domain.model.InvalidLabelsException;
import pl.jacekk.azureprovisioningservice.domain.model.JobLease;
import pl.jacekk.azureprovisioningservice.domain.model.Labels;
import pl.jacekk.azureprovisioningservice.domain.model.ProvisioningStatus;
import pl.jacekk.azureprovisioningservice.domain.port.in.AcceptanceOutcome;
import pl.jacekk.azureprovisioningservice.domain.port.in.AccountAcceptance;
import pl.jacekk.azureprovisioningservice.domain.port.in.CreateAccountCommand;
import pl.jacekk.azureprovisioningservice.domain.port.out.AccountRepositoryPort;
import pl.jacekk.azureprovisioningservice.domain.port.out.DuplicateSubscriptionNameException;
import pl.jacekk.azureprovisioningservice.domain.port.out.LabelValidationPort;
import pl.jacekk.azureprovisioningservice.domain.port.out.LabelValidationResult;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class AccountProvisioningServiceTest {

    private static final Instant NOW = Instant.parse("2026-09-11T10:00:00Z");
    private static final Map<String, String> VALID_LABELS = Map.of(
            Labels.COST_CENTER_ID, "CC-1001",
            Labels.COST_CENTER_ID_PROVIDER, "sap",
            Labels.PROJECT_INTERNAL_ID, "PRJ-42",
            Labels.PROJECT_INTERNAL_ID_PROVIDER, "servicenow");

    private final AccountRepositoryPort accounts = mock(AccountRepositoryPort.class);
    private final LabelValidationPort labelValidation = mock(LabelValidationPort.class);
    private final ProvisioningWorkflow workflow = mock(ProvisioningWorkflow.class);
    private final ProvisioningProperties properties =
            new ProvisioningProperties(Duration.ofMinutes(5), Duration.ofMinutes(1), 100, "replica-a");

    private AccountProvisioningService service;

    @BeforeEach
    void setUp() {
        service = new AccountProvisioningService(accounts, labelValidation, workflow,
                Clock.fixed(NOW, ZoneOffset.UTC), properties);
        when(labelValidation.validate(any())).thenReturn(LabelValidationResult.accepted());
        when(accounts.insertNew(any())).thenAnswer(invocation -> invocation.getArgument(0));
    }

    private static CreateAccountCommand command() {
        return new CreateAccountCommand("team-alpha-prod", "mg-workloads", VALID_LABELS);
    }

    private static Account existingAccount(ProvisioningStatus status) {
        return Account.restore()
                .id("acc-existing")
                .subscriptionName("team-alpha-prod")
                .targetManagementGroup("mg-workloads")
                .labels(Labels.of(VALID_LABELS))
                .status(status)
                .createdAt(NOW)
                .updatedAt(NOW)
                .jobId("job-old")
                .ownerId("replica-z")
                .leaseExpiresAt(NOW.plusSeconds(300))
                .build();
    }

    @Test
    void rejectsInvalidLabelsBeforeAnythingIsPersisted() {
        when(labelValidation.validate(any()))
                .thenReturn(LabelValidationResult.of(List.of("label 'cost-center-id' is required")));

        assertThatThrownBy(() -> service.createAccount(command()))
                .isInstanceOf(InvalidLabelsException.class)
                .satisfies(thrown -> assertThat(((InvalidLabelsException) thrown).getViolations())
                        .containsExactly("label 'cost-center-id' is required"));

        verifyNoInteractions(accounts);
        verifyNoInteractions(workflow);
    }

    @Test
    void acceptsAFreshRequestAndDispatchesTheProvisioningRun() {
        AccountAcceptance acceptance = service.createAccount(command());

        ArgumentCaptor<Account> inserted = ArgumentCaptor.forClass(Account.class);
        verify(accounts).insertNew(inserted.capture());

        assertThat(acceptance.outcome()).isEqualTo(AcceptanceOutcome.CREATED);
        assertThat(acceptance.accountId()).isEqualTo(inserted.getValue().getId());
        assertThat(inserted.getValue().getStatus()).isEqualTo(ProvisioningStatus.PENDING);
        assertThat(inserted.getValue().getSubscriptionName()).isEqualTo("team-alpha-prod");
        assertThat(inserted.getValue().getLabels()).isEqualTo(Labels.of(VALID_LABELS));
        verify(workflow).runFresh(acceptance.accountId());
        verify(workflow, never()).runRetry(any());
    }

    @Test
    void stampsAFreshJobAndLeaseOnAnAcceptedRequest() {
        service.createAccount(command());

        ArgumentCaptor<Account> inserted = ArgumentCaptor.forClass(Account.class);
        verify(accounts).insertNew(inserted.capture());

        assertThat(inserted.getValue().getJobId()).isNotBlank();
        assertThat(inserted.getValue().getOwnerId()).isEqualTo("replica-a");
        assertThat(inserted.getValue().getLeaseExpiresAt()).isEqualTo(NOW.plus(Duration.ofMinutes(5)));
    }

    @Test
    void reportsAConflictWhenTheNameIsHeldByAJobThatHasNotFailed() {
        when(accounts.insertNew(any())).thenThrow(new DuplicateSubscriptionNameException("team-alpha-prod"));
        when(accounts.claimForRetry(eq("team-alpha-prod"), any(), any())).thenReturn(Optional.empty());
        when(accounts.findBySubscriptionName("team-alpha-prod"))
                .thenReturn(Optional.of(existingAccount(ProvisioningStatus.CREATING_SUBSCRIPTION)));

        AccountAcceptance acceptance = service.createAccount(command());

        assertThat(acceptance.outcome()).isEqualTo(AcceptanceOutcome.ALREADY_IN_PROGRESS);
        assertThat(acceptance.accountId()).isEqualTo("acc-existing");
        verifyNoInteractions(workflow);
    }

    @Test
    void acceptsARetryWhenTheClaimOnAFailedJobSucceeds() {
        Account claimed = existingAccount(ProvisioningStatus.FAILED);
        claimed.startCleanup(new JobLease("job-new", "replica-a", NOW.plusSeconds(300)), NOW);
        when(accounts.insertNew(any())).thenThrow(new DuplicateSubscriptionNameException("team-alpha-prod"));
        when(accounts.claimForRetry(eq("team-alpha-prod"), any(), any())).thenReturn(Optional.of(claimed));

        AccountAcceptance acceptance = service.createAccount(command());

        assertThat(acceptance.outcome()).isEqualTo(AcceptanceOutcome.RETRY_ACCEPTED);
        assertThat(acceptance.accountId()).isEqualTo("acc-existing");
        verify(workflow).runRetry("acc-existing");
        verify(workflow, never()).runFresh(any());
    }

    @Test
    void claimsTheRetryUnderAFreshCorrelationIdAndLease() {
        Account claimed = existingAccount(ProvisioningStatus.FAILED);
        claimed.startCleanup(new JobLease("job-new", "replica-a", NOW.plusSeconds(300)), NOW);
        when(accounts.insertNew(any())).thenThrow(new DuplicateSubscriptionNameException("team-alpha-prod"));
        when(accounts.claimForRetry(any(), any(), any())).thenReturn(Optional.of(claimed));

        service.createAccount(command());

        ArgumentCaptor<JobLease> lease = ArgumentCaptor.forClass(JobLease.class);
        verify(accounts).claimForRetry(eq("team-alpha-prod"), lease.capture(), eq(NOW));

        assertThat(lease.getValue().jobId()).isNotBlank().isNotEqualTo("job-old");
        assertThat(lease.getValue().ownerId()).isEqualTo("replica-a");
        assertThat(lease.getValue().expiresAt()).isEqualTo(NOW.plus(Duration.ofMinutes(5)));
    }

    @Test
    void failsLoudlyIfTheConflictingAccountDisappearsMidRace() {
        when(accounts.insertNew(any())).thenThrow(new DuplicateSubscriptionNameException("team-alpha-prod"));
        when(accounts.claimForRetry(any(), any(), any())).thenReturn(Optional.empty());
        when(accounts.findBySubscriptionName(any())).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.createAccount(command()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("team-alpha-prod");
    }

    @Test
    void returnsTheStoredAccountForAKnownId() {
        Account stored = existingAccount(ProvisioningStatus.COMPLETED);
        when(accounts.findById("acc-existing")).thenReturn(Optional.of(stored));

        assertThat(service.getAccount("acc-existing")).isSameAs(stored);
    }

    @Test
    void reportsAnUnknownAccountId() {
        when(accounts.findById("nope")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.getAccount("nope"))
                .isInstanceOf(AccountNotFoundException.class)
                .hasMessageContaining("nope");
    }
}
