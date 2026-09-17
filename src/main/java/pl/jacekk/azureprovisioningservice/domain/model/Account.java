package pl.jacekk.azureprovisioningservice.domain.model;

import lombok.Builder;
import lombok.Getter;

import java.time.Instant;
import java.util.Objects;

/**
 * The provisioning job aggregate.
 *
 * <p>State changes go through the intention-revealing methods below rather than setters: each one
 * validates the move against {@link ProvisioningStatus}'s transition table, so an out-of-order
 * workflow surfaces as a failing test instead of a corrupt document.
 */
@Getter
public class Account {

    private final String id;
    private final String subscriptionName;
    private final String targetManagementGroup;
    private final Labels labels;
    private final Instant createdAt;

    private ProvisioningStatus status;
    private Instant updatedAt;
    private String azureSubscriptionId;
    private String errorDetail;

    /** Correlation id of the run currently owning this account. A retry mints a fresh one. */
    private String jobId;
    private String ownerId;
    private Instant leaseExpiresAt;

    /** Opaque optimistic-locking token, owned by the persistence adapter. */
    private Long version;

    @Builder(builderMethodName = "restore", builderClassName = "Restorer")
    private Account(String id,
                    String subscriptionName,
                    String targetManagementGroup,
                    Labels labels,
                    ProvisioningStatus status,
                    Instant createdAt,
                    Instant updatedAt,
                    String azureSubscriptionId,
                    String errorDetail,
                    String jobId,
                    String ownerId,
                    Instant leaseExpiresAt,
                    Long version) {
        this.id = Objects.requireNonNull(id, "id must not be null");
        this.subscriptionName = Objects.requireNonNull(subscriptionName, "subscriptionName must not be null");
        this.targetManagementGroup = Objects.requireNonNull(targetManagementGroup, "targetManagementGroup must not be null");
        this.labels = Objects.requireNonNull(labels, "labels must not be null");
        this.status = Objects.requireNonNull(status, "status must not be null");
        this.createdAt = Objects.requireNonNull(createdAt, "createdAt must not be null");
        this.updatedAt = Objects.requireNonNull(updatedAt, "updatedAt must not be null");
        this.azureSubscriptionId = azureSubscriptionId;
        this.errorDetail = errorDetail;
        this.jobId = jobId;
        this.ownerId = ownerId;
        this.leaseExpiresAt = leaseExpiresAt;
        this.version = version;
    }

    public static Account newRequest(String id,
                                     String subscriptionName,
                                     String targetManagementGroup,
                                     Labels labels,
                                     JobLease lease,
                                     Instant now) {
        Objects.requireNonNull(lease, "lease must not be null");
        return Account.restore()
                .id(id)
                .subscriptionName(subscriptionName)
                .targetManagementGroup(targetManagementGroup)
                .labels(labels)
                .status(ProvisioningStatus.PENDING)
                .createdAt(now)
                .updatedAt(now)
                .jobId(lease.jobId())
                .ownerId(lease.ownerId())
                .leaseExpiresAt(lease.expiresAt())
                .build();
    }

    // --- provisioning path -------------------------------------------------------------------

    /** Step 1 of a run. A retry arrives here exactly as a fresh request does. */
    public void startProvisioning(Instant now) {
        transitionTo(ProvisioningStatus.CREATING_SUBSCRIPTION, now);
    }

    /**
     * Records the subscription this account owns, whether step 1 created it or adopted one an
     * earlier run had already built. Being handed a <em>different</em> id means two subscriptions
     * exist for one account, so it fails loudly rather than forgetting the first.
     */
    public void recordSubscription(String azureSubscriptionId, Instant now) {
        requireStatus(ProvisioningStatus.CREATING_SUBSCRIPTION);
        Objects.requireNonNull(azureSubscriptionId, "azureSubscriptionId must not be null");
        if (hasAzureSubscription() && !this.azureSubscriptionId.equals(azureSubscriptionId)) {
            throw new IllegalStateException("Account %s already owns subscription %s, refusing to replace it with %s"
                    .formatted(id, this.azureSubscriptionId, azureSubscriptionId));
        }
        this.azureSubscriptionId = azureSubscriptionId;
        touch(now);
    }

    public void startAssigningManagementGroup(Instant now) {
        transitionTo(ProvisioningStatus.ASSIGNING_MANAGEMENT_GROUP, now);
    }

    public void startApplyingLabels(Instant now) {
        transitionTo(ProvisioningStatus.APPLYING_LABELS, now);
    }

    public void complete(Instant now) {
        transitionTo(ProvisioningStatus.COMPLETED, now);
        this.errorDetail = null;
    }

    /**
     * Fails the step currently in progress. The partially created subscription is deliberately
     * left in place — the next retry's cleanup deletes it.
     */
    public void failStep(String cause, Instant now) {
        ProvisioningStatus failedAt = status;
        transitionTo(ProvisioningStatus.FAILED, now);
        this.errorDetail = "Step %s failed: %s".formatted(failedAt, cause);
    }

    // --- retry -------------------------------------------------------------------------------

    /**
     * Claims a failed account as a retry, under a fresh lease. Whatever the failed run built is
     * kept: the rerun's first step adopts it rather than creating a second subscription.
     */
    public void startRetry(JobLease lease, Instant now) {
        Objects.requireNonNull(lease, "lease must not be null");
        transitionTo(ProvisioningStatus.PENDING, now);
        this.jobId = lease.jobId();
        this.ownerId = lease.ownerId();
        this.leaseExpiresAt = lease.expiresAt();
        this.errorDetail = null;
    }

    // --- lease -------------------------------------------------------------------------------

    /** Marks a job whose owning replica died as failed, making it eligible for an ordinary retry. */
    public void abandon(Instant now) {
        ProvisioningStatus diedIn = status;
        if (!diedIn.isInFlight()) {
            throw new IllegalStatusTransitionException(diedIn, ProvisioningStatus.FAILED);
        }
        transitionTo(ProvisioningStatus.FAILED, now);
        this.errorDetail =
                "ABANDONED: lease held by %s expired while status was %s".formatted(ownerId, diedIn);
    }

    public void renewLease(Instant expiresAt, Instant now) {
        this.leaseExpiresAt = Objects.requireNonNull(expiresAt, "expiresAt must not be null");
        touch(now);
    }

    public boolean isLeaseExpired(Instant now) {
        return leaseExpiresAt != null && !now.isBefore(leaseExpiresAt);
    }

    /** Called by the persistence adapter after a successful write. */
    public void applyPersistedVersion(Long version) {
        this.version = version;
    }

    /**
     * The Azure subscription alias this account owns, for the life of the account.
     *
     * <p>Derived rather than stored, and deliberately stable: it is how a rerun finds the
     * subscription an earlier run built and adopts it. That also covers the run that created a
     * subscription and died before recording its id — the alias survives what the crash lost.
     */
    public String provisioningAlias() {
        return "acct-" + id;
    }

    public boolean hasAzureSubscription() {
        return azureSubscriptionId != null;
    }

    // --- internals ---------------------------------------------------------------------------

    private void transitionTo(ProvisioningStatus target, Instant now) {
        if (!status.canTransitionTo(target)) {
            throw new IllegalStatusTransitionException(status, target);
        }
        this.status = target;
        touch(now);
    }

    private void requireStatus(ProvisioningStatus expected) {
        if (status != expected) {
            throw new IllegalStatusTransitionException(status, expected);
        }
    }

    private void touch(Instant now) {
        this.updatedAt = Objects.requireNonNull(now, "now must not be null");
    }
}
