package pl.jacekk.azureprovisioningservice.domain.port.out;

import pl.jacekk.azureprovisioningservice.domain.model.Labels;

/**
 * The subscription itself.
 *
 * <p>Both operations state a desired end state rather than an action, so running them twice is
 * harmless. That is what lets a retry rerun every step instead of having to undo the last one —
 * and it keeps the decision about whether work is needed with Azure, which knows, rather than with
 * our own record of where a previous run got to, which may be stale or lost.
 */
public interface AzureSubscriptionPort {

    /**
     * Ensures a subscription exists under {@code alias}, adopting the one an earlier run created
     * rather than making a second.
     *
     * @return the Azure subscription id
     * @throws AzureProvisioningException when Azure refuses
     */
    String ensureSubscription(String alias, String subscriptionName);

    /** Ensures the subscription carries exactly these tags, correcting any drift. */
    void ensureTags(String azureSubscriptionId, Labels labels);
}
