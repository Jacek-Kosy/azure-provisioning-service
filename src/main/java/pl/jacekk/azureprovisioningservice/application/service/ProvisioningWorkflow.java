package pl.jacekk.azureprovisioningservice.application.service;

/**
 * The async half of the use case, kept behind an interface (and in its own bean) so that
 * {@code @Async} proxying actually applies — a self-invocation from the service would run inline —
 * and so unit tests can drive it synchronously.
 */
public interface ProvisioningWorkflow {

    /** Runs every step. A retry is the same thing as a fresh run, because each step reconciles. */
    void run(String accountId);
}
