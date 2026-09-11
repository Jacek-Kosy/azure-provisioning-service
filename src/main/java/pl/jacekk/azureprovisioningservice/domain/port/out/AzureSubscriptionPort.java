package pl.jacekk.azureprovisioningservice.domain.port.out;

import pl.jacekk.azureprovisioningservice.domain.model.Labels;

/** Creating, tagging and deleting the Azure subscription itself. */
public interface AzureSubscriptionPort {

    /**
     * Creates the subscription.
     *
     * @return the Azure subscription id
     * @throws AzureProvisioningException when Azure refuses
     */
    String createSubscription(String subscriptionName);

    /** Applies the validated labels to the subscription as tags. */
    void applyTags(String azureSubscriptionId, Labels labels);

    /**
     * Deletes (cancels) a subscription left behind by a failed run. Called by step 0 of a retry,
     * never immediately after the failure itself.
     */
    void deleteSubscription(String azureSubscriptionId);
}
