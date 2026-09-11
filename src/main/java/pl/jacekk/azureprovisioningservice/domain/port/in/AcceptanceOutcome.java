package pl.jacekk.azureprovisioningservice.domain.port.in;

/**
 * What the use case decided about a request. Deliberately free of HTTP concepts — the web adapter
 * maps {@code CREATED} and {@code RETRY_ACCEPTED} to 202 and {@code ALREADY_IN_PROGRESS} to 409.
 */
public enum AcceptanceOutcome {

    CREATED,
    RETRY_ACCEPTED,
    ALREADY_IN_PROGRESS;

    public boolean startedWork() {
        return this != ALREADY_IN_PROGRESS;
    }
}
