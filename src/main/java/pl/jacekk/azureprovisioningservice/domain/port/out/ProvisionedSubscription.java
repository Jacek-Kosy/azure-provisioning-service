package pl.jacekk.azureprovisioningservice.domain.port.out;

public record ProvisionedSubscription(String azureSubscriptionId, ReconcileOutcome outcome) {
}
