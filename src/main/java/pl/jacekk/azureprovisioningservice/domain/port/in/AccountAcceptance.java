package pl.jacekk.azureprovisioningservice.domain.port.in;

public record AccountAcceptance(String accountId, AcceptanceOutcome outcome) {
}
