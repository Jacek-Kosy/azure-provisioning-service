package pl.jacekk.azureprovisioningservice.domain.port.in;

import java.util.Map;

/** A request to provision one subscription. Labels arrive raw, so they can be validated first. */
public record CreateAccountCommand(String subscriptionName,
                                   String targetManagementGroup,
                                   Map<String, String> labels) {
}
