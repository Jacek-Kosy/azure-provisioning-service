package pl.jacekk.azureprovisioningservice.domain.port.out;

/** The subscription name — the idempotency key — is already taken. */
public class DuplicateSubscriptionNameException extends RuntimeException {

    public DuplicateSubscriptionNameException(String subscriptionName) {
        super("Subscription name '%s' is already taken".formatted(subscriptionName));
    }
}
