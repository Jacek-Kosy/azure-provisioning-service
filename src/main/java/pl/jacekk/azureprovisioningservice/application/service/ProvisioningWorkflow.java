package pl.jacekk.azureprovisioningservice.application.service;

/**
 * The async half of the use case, kept behind an interface (and in its own bean) so that
 * {@code @Async} proxying actually applies — a self-invocation from the service would run inline —
 * and so unit tests can drive it synchronously.
 */
public interface ProvisioningWorkflow {

    /** A brand new job: run every step from step 1. */
    void runFresh(String accountId);

    /** A retry: clean up what the previous run left behind, then run every step from step 1. */
    void runRetry(String accountId);
}
