package pl.jacekk.azureprovisioningservice.config;

import com.azure.core.credential.TokenCredential;
import com.azure.identity.DefaultAzureCredentialBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Azure credentials, resolved from the environment — never from configuration or source.
 *
 * <p>{@code DefaultAzureCredential} tries, in order: environment variables
 * ({@code AZURE_CLIENT_ID}, {@code AZURE_TENANT_ID}, {@code AZURE_CLIENT_SECRET} or a certificate),
 * workload identity, managed identity, and finally a developer's Azure CLI login. In a cluster the
 * managed-identity step is the one that matters, and no secret exists to leak. Tokens are fetched
 * lazily on first use, so an unconfigured environment fails at the Azure call rather than at
 * startup.
 */
@Configuration
public class AzureCredentialConfig {

    @Bean
    public TokenCredential azureCredential() {
        return new DefaultAzureCredentialBuilder().build();
    }
}
