package pl.jacekk.azureprovisioningservice.adapter.out.azure;

import com.azure.core.credential.TokenCredential;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import pl.jacekk.azureprovisioningservice.config.AzureCredentialConfig;
import pl.jacekk.azureprovisioningservice.config.AzureProperties;
import pl.jacekk.azureprovisioningservice.domain.model.Labels;
import pl.jacekk.azureprovisioningservice.domain.port.out.AzureSubscriptionPort;
import pl.jacekk.azureprovisioningservice.domain.port.out.ManagementGroupPort;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

class StubAzureAdaptersTest {

    private static final Labels LABELS = Labels.of(Map.of(
            Labels.COST_CENTER_ID, "CC-1001",
            Labels.COST_CENTER_ID_PROVIDER, "sap",
            Labels.PROJECT_INTERNAL_ID, "PRJ-42",
            Labels.PROJECT_INTERNAL_ID_PROVIDER, "servicenow"));

    private final AzureProperties properties = new AzureProperties("/billing/scope", "mg-root");
    private final AzureSubscriptionPort subscriptions = new StubAzureSubscriptionAdapter(properties);
    private final ManagementGroupPort managementGroups = new StubManagementGroupAdapter(properties);

    @Test
    void createsASubscriptionTheFirstTimeAnAliasIsUsed() {
        assertThat(subscriptions.ensureSubscription("acct-1", "team-alpha-prod")).isNotBlank();
    }

    @Test
    void adoptsTheSameSubscriptionWhenTheAliasIsUsedAgain() {
        String first = subscriptions.ensureSubscription("acct-1", "team-alpha-prod");

        assertThat(subscriptions.ensureSubscription("acct-1", "team-alpha-prod")).isEqualTo(first);
    }

    @Test
    void givesADistinctSubscriptionToADistinctAlias() {
        assertThat(subscriptions.ensureSubscription("acct-1", "team-alpha-prod"))
                .isNotEqualTo(subscriptions.ensureSubscription("acct-2", "team-beta-prod"));
    }

    @Test
    void toleratesEveryStepBeingRunAgain() {
        String id = subscriptions.ensureSubscription("acct-1", "team-alpha-prod");
        subscriptions.ensureTags(id, LABELS);
        managementGroups.ensurePlacedUnder(id, "mg-workloads");

        assertThatCode(() -> {
            subscriptions.ensureTags(id, LABELS);
            managementGroups.ensurePlacedUnder(id, "mg-workloads");
            managementGroups.ensurePlacedUnder(id, "mg-sandbox");
        }).doesNotThrowAnyException();
    }

    @Test
    void resolvesAzureCredentialsFromTheEnvironmentRatherThanConfiguration() {
        new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of())
                .withUserConfiguration(AzureCredentialConfig.class)
                .withBean(AzureProperties.class, () -> properties)
                .run(context -> {
                    assertThat(context).hasSingleBean(TokenCredential.class);
                    // Nothing secret may be bound into configuration properties.
                    assertThat(AzureProperties.class.getRecordComponents())
                            .extracting(java.lang.reflect.RecordComponent::getName)
                            .doesNotContain("clientSecret", "password", "secret", "key");
                });
    }
}
