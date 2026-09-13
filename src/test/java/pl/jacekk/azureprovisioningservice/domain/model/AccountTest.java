package pl.jacekk.azureprovisioningservice.domain.model;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AccountTest {

    private static final Instant NOW = Instant.parse("2026-09-11T10:00:00Z");
    private static final Instant LATER = Instant.parse("2026-09-11T10:05:00Z");
    private static final JobLease LEASE = new JobLease("job-1", "replica-a", NOW.plusSeconds(60));

    private static Account pendingAccount() {
        return Account.newRequest("acc-1", "team-alpha-prod", "mg-workloads", labels(), LEASE, NOW);
    }

    private static Labels labels() {
        return Labels.of(Map.of(
                Labels.COST_CENTER_ID, "CC-1001",
                Labels.COST_CENTER_ID_PROVIDER, "sap",
                Labels.PROJECT_INTERNAL_ID, "PRJ-42",
                Labels.PROJECT_INTERNAL_ID_PROVIDER, "servicenow"));
    }

    private static Account failedAccountWithSubscription() {
        Account account = pendingAccount();
        account.startProvisioning(NOW);
        account.recordSubscriptionCreated("sub-123", NOW);
        account.startAssigningManagementGroup(NOW);
        account.failStep("management group not found", NOW);
        return account;
    }

    @Test
    void newRequestStartsPending() {
        Account account = pendingAccount();

        assertThat(account.getStatus()).isEqualTo(ProvisioningStatus.PENDING);
        assertThat(account.getId()).isEqualTo("acc-1");
        assertThat(account.getSubscriptionName()).isEqualTo("team-alpha-prod");
        assertThat(account.getTargetManagementGroup()).isEqualTo("mg-workloads");
        assertThat(account.getLabels()).isEqualTo(labels());
        assertThat(account.getJobId()).isEqualTo("job-1");
        assertThat(account.getOwnerId()).isEqualTo("replica-a");
        assertThat(account.getCreatedAt()).isEqualTo(NOW);
        assertThat(account.getUpdatedAt()).isEqualTo(NOW);
        assertThat(account.getAzureSubscriptionId()).isNull();
        assertThat(account.getErrorDetail()).isNull();
    }

    @Test
    void walksTheFullProvisioningPath() {
        Account account = pendingAccount();

        account.startProvisioning(LATER);
        assertThat(account.getStatus()).isEqualTo(ProvisioningStatus.CREATING_SUBSCRIPTION);

        account.recordSubscriptionCreated("sub-123", LATER);
        assertThat(account.getAzureSubscriptionId()).isEqualTo("sub-123");
        assertThat(account.getStatus()).isEqualTo(ProvisioningStatus.CREATING_SUBSCRIPTION);

        account.startAssigningManagementGroup(LATER);
        assertThat(account.getStatus()).isEqualTo(ProvisioningStatus.ASSIGNING_MANAGEMENT_GROUP);

        account.startApplyingLabels(LATER);
        assertThat(account.getStatus()).isEqualTo(ProvisioningStatus.APPLYING_LABELS);

        account.complete(LATER);
        assertThat(account.getStatus()).isEqualTo(ProvisioningStatus.COMPLETED);
        assertThat(account.getErrorDetail()).isNull();
    }

    @Test
    void advancesUpdatedAtOnEveryTransition() {
        Account account = pendingAccount();

        account.startProvisioning(LATER);

        assertThat(account.getUpdatedAt()).isEqualTo(LATER);
        assertThat(account.getCreatedAt()).isEqualTo(NOW);
    }

    @Test
    void rejectsStepSkipping() {
        Account account = pendingAccount();

        assertThatThrownBy(() -> account.startApplyingLabels(LATER))
                .isInstanceOf(IllegalStatusTransitionException.class)
                .hasMessageContaining("PENDING")
                .hasMessageContaining("APPLYING_LABELS");

        assertThat(account.getStatus()).isEqualTo(ProvisioningStatus.PENDING);
    }

    @Test
    void rejectsTransitionsOutOfATerminalStatus() {
        Account account = pendingAccount();
        account.startProvisioning(NOW);
        account.recordSubscriptionCreated("sub-123", NOW);
        account.startAssigningManagementGroup(NOW);
        account.startApplyingLabels(NOW);
        account.complete(NOW);

        assertThatThrownBy(() -> account.startCleanup(LEASE, LATER))
                .isInstanceOf(IllegalStatusTransitionException.class);
    }

    @Test
    void failureNamesTheStepAndTheCause() {
        Account account = failedAccountWithSubscription();

        assertThat(account.getStatus()).isEqualTo(ProvisioningStatus.FAILED);
        assertThat(account.getErrorDetail())
                .isEqualTo("Step ASSIGNING_MANAGEMENT_GROUP failed: management group not found");
    }

    @Test
    void failureLeavesThePartiallyCreatedSubscriptionInPlace() {
        Account account = failedAccountWithSubscription();

        assertThat(account.getAzureSubscriptionId()).isEqualTo("sub-123");
        assertThat(account.hasAzureSubscription()).isTrue();
    }

    @Test
    void retryClaimClearsTheErrorAndTakesAFreshLease() {
        Account account = failedAccountWithSubscription();
        JobLease retryLease = new JobLease("job-2", "replica-b", LATER.plusSeconds(60));

        account.startCleanup(retryLease, LATER);

        assertThat(account.getStatus()).isEqualTo(ProvisioningStatus.CLEANING_UP);
        assertThat(account.getErrorDetail()).isNull();
        assertThat(account.getJobId()).isEqualTo("job-2");
        assertThat(account.getOwnerId()).isEqualTo("replica-b");
        assertThat(account.getAzureSubscriptionId())
                .as("the orphaned subscription is still there until cleanup deletes it")
                .isEqualTo("sub-123");
    }

    @Test
    void cleanupClearsTheAzureSubscriptionId() {
        Account account = failedAccountWithSubscription();
        account.startCleanup(LEASE, LATER);

        account.markCleanedUp(LATER);

        assertThat(account.getAzureSubscriptionId()).isNull();
        assertThat(account.hasAzureSubscription()).isFalse();
        assertThat(account.getStatus()).isEqualTo(ProvisioningStatus.CLEANING_UP);
    }

    @Test
    void cleanupFailureIsDistinguishableFromAProvisioningFailure() {
        Account account = failedAccountWithSubscription();
        account.startCleanup(LEASE, LATER);

        account.failCleanup("delete rejected by Azure", LATER);

        assertThat(account.getStatus()).isEqualTo(ProvisioningStatus.FAILED);
        assertThat(account.getErrorDetail()).isEqualTo("CLEANUP_FAILED: delete rejected by Azure");
        assertThat(account.getErrorDetail()).doesNotContain("Step ");
    }

    @Test
    void rerunAfterCleanupStartsAgainAtStepOne() {
        Account account = failedAccountWithSubscription();
        account.startCleanup(LEASE, LATER);
        account.markCleanedUp(LATER);

        account.startProvisioning(LATER);

        assertThat(account.getStatus()).isEqualTo(ProvisioningStatus.CREATING_SUBSCRIPTION);
        assertThat(account.getAzureSubscriptionId()).isNull();
    }

    @Test
    void abandonedJobFailsNamingTheOwnerAndTheStatusItDiedIn() {
        Account account = pendingAccount();
        account.startProvisioning(NOW);

        account.abandon(LATER);

        assertThat(account.getStatus()).isEqualTo(ProvisioningStatus.FAILED);
        assertThat(account.getErrorDetail())
                .isEqualTo("ABANDONED: lease held by replica-a expired while status was CREATING_SUBSCRIPTION");
    }

    @Test
    void freshRequestHasNotAttemptedProvisioningYet() {
        assertThat(pendingAccount().getAttempt()).isZero();
    }

    @Test
    void countsEachProvisioningRunAsAnAttempt() {
        Account account = pendingAccount();

        account.startProvisioning(NOW);

        assertThat(account.getAttempt()).isEqualTo(1);
    }

    @Test
    void countsARerunAfterCleanupAsAFurtherAttempt() {
        Account account = failedAccountWithSubscription();
        account.startCleanup(LEASE, LATER);
        account.markCleanedUp(LATER);

        account.startProvisioning(LATER);

        assertThat(account.getAttempt()).isEqualTo(2);
    }

    @Test
    void derivesAStableAliasForTheCurrentAttempt() {
        Account account = pendingAccount();
        account.startProvisioning(NOW);

        assertThat(account.provisioningAlias()).isEqualTo("acct-acc-1-1");
        assertThat(account.provisioningAlias()).isEqualTo("acct-acc-1-1");
    }

    @Test
    void keepsTheFailedRunsAliasUntilTheNextAttemptStarts() {
        // Cleanup has to be able to name what the previous attempt created, even when that run
        // died before recording the subscription id.
        Account account = failedAccountWithSubscription();
        String aliasOfTheFailedRun = account.provisioningAlias();

        account.startCleanup(LEASE, LATER);

        assertThat(account.provisioningAlias()).isEqualTo(aliasOfTheFailedRun);

        account.markCleanedUp(LATER);
        account.startProvisioning(LATER);

        assertThat(account.provisioningAlias()).isNotEqualTo(aliasOfTheFailedRun);
    }

    @Test
    void leaseExpiryIsEvaluatedAgainstTheSuppliedInstant() {
        Account account = pendingAccount();

        assertThat(account.isLeaseExpired(NOW.plusSeconds(30))).isFalse();
        assertThat(account.isLeaseExpired(NOW.plusSeconds(90))).isTrue();
    }

    @Test
    void renewingTheLeasePushesItsExpiry() {
        Account account = pendingAccount();

        account.renewLease(LATER.plusSeconds(120), LATER);

        assertThat(account.isLeaseExpired(LATER.plusSeconds(90))).isFalse();
        assertThat(account.getUpdatedAt()).isEqualTo(LATER);
    }
}
