package pl.jacekk.azureprovisioningservice.domain.model;

/** Raised when the workflow tries to move an account somewhere the state machine forbids. */
public class IllegalStatusTransitionException extends RuntimeException {

    public IllegalStatusTransitionException(ProvisioningStatus from, ProvisioningStatus to) {
        super("Cannot transition from %s to %s".formatted(from, to));
    }
}
