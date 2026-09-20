package pl.jacekk.azureprovisioningservice.domain.port.out;

/** Where the subscription sits in the management-group hierarchy. */
public interface ManagementGroupPort {

    /**
     * Ensures the subscription sits directly under the given management group, moving it only if
     * it is somewhere else.
     *
     * @throws AzureProvisioningException when the move is refused
     */
    void ensurePlacedUnder(String azureSubscriptionId, String managementGroupId);
}
