package pl.jacekk.azureprovisioningservice.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Non-secret routing data for the Azure adapters.
 *
 * <p>Credentials are deliberately absent: they are resolved at runtime by
 * {@link AzureCredentialConfig} from environment variables or a managed identity, so no secret is
 * ever bound into configuration, written to a config file, or available to be logged.
 *
 * @param billingScope billing scope / enrollment account new subscriptions are charged to
 * @param rootManagementGroup management group used when a request does not name one
 */
@ConfigurationProperties(prefix = "azure")
public record AzureProperties(String billingScope, String rootManagementGroup) {
}
