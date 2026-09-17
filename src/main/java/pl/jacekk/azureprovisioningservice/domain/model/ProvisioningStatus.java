package pl.jacekk.azureprovisioningservice.domain.model;

import java.util.EnumSet;
import java.util.Set;

/**
 * The lifecycle of a provisioning job.
 *
 * <p>This is not a label attached to the side of the workflow — it <em>is</em> the workflow's
 * state, and it is also the lock that keeps concurrent replicas from double-provisioning: the
 * retry claim is a compare-and-set on this field. Keep the transition table here so the
 * aggregate and the persistence adapter agree on what a legal move is.
 */
public enum ProvisioningStatus {

    PENDING,
    CREATING_SUBSCRIPTION,
    ASSIGNING_MANAGEMENT_GROUP,
    APPLYING_LABELS,
    COMPLETED,
    FAILED;

    public boolean isTerminal() {
        return this == COMPLETED || this == FAILED;
    }

    public boolean isInFlight() {
        return !isTerminal();
    }

    /** Only a failed job may be claimed as a retry. */
    public boolean isRetryable() {
        return this == FAILED;
    }

    public boolean canTransitionTo(ProvisioningStatus target) {
        return allowedTargets().contains(target);
    }

    private Set<ProvisioningStatus> allowedTargets() {
        return switch (this) {
            case PENDING -> EnumSet.of(CREATING_SUBSCRIPTION, FAILED);
            case CREATING_SUBSCRIPTION -> EnumSet.of(ASSIGNING_MANAGEMENT_GROUP, FAILED);
            case ASSIGNING_MANAGEMENT_GROUP -> EnumSet.of(APPLYING_LABELS, FAILED);
            case APPLYING_LABELS -> EnumSet.of(COMPLETED, FAILED);
            case COMPLETED -> EnumSet.noneOf(ProvisioningStatus.class);
            // A retry restarts the run. Every step reconciles, so there is nothing to undo first.
            case FAILED -> EnumSet.of(PENDING);
        };
    }
}
