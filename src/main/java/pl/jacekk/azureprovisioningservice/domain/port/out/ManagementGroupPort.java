package pl.jacekk.azureprovisioningservice.domain.port.out;

/** Placing a subscription under its target management group. */
public interface ManagementGroupPort {

    /**
     * @throws AzureProvisioningException when the move is refused
     */
    void assignSubscription(String azureSubscriptionId, String managementGroupId);
}
