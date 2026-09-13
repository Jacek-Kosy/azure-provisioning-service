package pl.jacekk.azureprovisioningservice.domain.port.out;

import pl.jacekk.azureprovisioningservice.domain.model.Labels;

import java.util.Optional;

/** Creating, tagging and deleting the Azure subscription itself. */
public interface AzureSubscriptionPort {

    /**
     * Creates the subscription under {@code alias} — Azure's own idempotency key for a
     * subscription, and the handle that makes a crashed attempt recoverable.
     *
     * @param alias the current attempt's alias, from {@code Account.provisioningAlias()}
     * @return the Azure subscription id
     * @throws AzureProvisioningException when Azure refuses
     */
    String createSubscription(String alias, String subscriptionName);

    /**
     * Looks up what a given alias created.
     *
     * <p>This is how a subscription created by a run that died before recording its id is found
     * again instead of being leaked: the alias is derived from the account and its attempt, so it
     * survives the crash even though the id did not.
     *
     * @return the subscription id, or empty if that attempt never got as far as creating one
     */
    Optional<String> findSubscriptionIdByAlias(String alias);

    /** Applies the validated labels to the subscription as tags. */
    void applyTags(String azureSubscriptionId, Labels labels);

    /**
     * Deletes (cancels) a subscription left behind by a failed run. Called by step 0 of a retry,
     * never immediately after the failure itself.
     *
     * <p>Must be idempotent: cleanup can legitimately run twice against the same subscription if a
     * replica dies between deleting it and recording that it did.
     */
    void deleteSubscription(String azureSubscriptionId);
}
