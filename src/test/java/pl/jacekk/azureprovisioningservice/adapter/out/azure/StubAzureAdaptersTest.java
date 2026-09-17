package pl.jacekk.azureprovisioningservice.adapter.out.azure;

import com.azure.core.credential.TokenCredential;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import pl.jacekk.azureprovisioningservice.config.AzureCredentialConfig;
import pl.jacekk.azureprovisioningservice.config.AzureProperties;
import pl.jacekk.azureprovisioningservice.domain.model.Labels;
import pl.jacekk.azureprovisioningservice.domain.port.out.AzureSubscriptionPort;
import pl.jacekk.azureprovisioningservice.domain.port.out.ProvisionedSubscription;
import pl.jacekk.azureprovisioningservice.domain.port.out.ReconcileOutcome;
import pl.jacekk.azureprovisioningservice.domain.port.out.ManagementGroupPort;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class StubAzureAdaptersTest {

    private static final Labels LABELS = Labels.of(Map.of(
            Labels.COST_CENTER_ID, "CC-1001",
            Labels.COST_CENTER_ID_PROVIDER, "sap",
            Labels.PROJECT_INTERNAL_ID, "PRJ-42",
            Labels.PROJECT_INTERNAL_ID_PROVIDER, "servicenow"));

    private final AzureProperties properties = new AzureProperties("tenant-1", "/billing/scope", "mg-root");
    private final AzureSubscriptionPort subscriptions = new StubAzureSubscriptionAdapter(properties);
    private final ManagementGroupPort managementGroups = new StubManagementGroupAdapter(properties);

    @Test
    void createsASubscriptionTheFirstTimeAnAliasIsUsed() {
        ProvisionedSubscription provisioned = subscriptions.ensureSubscription("acct-1", "team-alpha-prod");

        assertThat(provisioned.azureSubscriptionId()).isNotBlank();
        assertThat(provisioned.outcome()).isEqualTo(ReconcileOutcome.CREATED);
    }

    @Test
    void adoptsTheSameSubscriptionWhenTheAliasIsUsedAgain() {
        ProvisionedSubscription first = subscriptions.ensureSubscription("acct-1", "team-alpha-prod");

        ProvisionedSubscription second = subscriptions.ensureSubscription("acct-1", "team-alpha-prod");

        assertThat(second.azureSubscriptionId()).isEqualTo(first.azureSubscriptionId());
        assertThat(second.outcome()).isEqualTo(ReconcileOutcome.ADOPTED);
    }

    @Test
    void givesADistinctSubscriptionToADistinctAlias() {
        assertThat(subscriptions.ensureSubscription("acct-1", "team-alpha-prod").azureSubscriptionId())
                .isNotEqualTo(subscriptions.ensureSubscription("acct-2", "team-beta-prod").azureSubscriptionId());
    }

    @Test
    void appliesTagsOnceAndThenLeavesThemAlone() {
        String id = subscriptions.ensureSubscription("acct-1", "team-alpha-prod").azureSubscriptionId();

        assertThat(subscriptions.ensureTags(id, LABELS)).isEqualTo(ReconcileOutcome.CREATED);
        assertThat(subscriptions.ensureTags(id, LABELS)).isEqualTo(ReconcileOutcome.ALREADY_SATISFIED);
    }

    @Test
    void correctsTagsThatHaveDrifted() {
        String id = subscriptions.ensureSubscription("acct-1", "team-alpha-prod").azureSubscriptionId();
        subscriptions.ensureTags(id, LABELS);

        Labels corrected = Labels.of(Map.of(
                Labels.COST_CENTER_ID, "CC-9999",
                Labels.COST_CENTER_ID_PROVIDER, "sap",
                Labels.PROJECT_INTERNAL_ID, "PRJ-42",
                Labels.PROJECT_INTERNAL_ID_PROVIDER, "servicenow"));

        assertThat(subscriptions.ensureTags(id, corrected)).isEqualTo(ReconcileOutcome.UPDATED);
        assertThat(subscriptions.ensureTags(id, corrected)).isEqualTo(ReconcileOutcome.ALREADY_SATISFIED);
    }

    @Test
    void placesASubscriptionOnceAndThenLeavesItAlone() {
        String id = subscriptions.ensureSubscription("acct-1", "team-alpha-prod").azureSubscriptionId();

        assertThat(managementGroups.ensurePlacedUnder(id, "mg-workloads")).isEqualTo(ReconcileOutcome.CREATED);
        assertThat(managementGroups.ensurePlacedUnder(id, "mg-workloads"))
                .isEqualTo(ReconcileOutcome.ALREADY_SATISFIED);
    }

    @Test
    void movesASubscriptionThatIsUnderTheWrongManagementGroup() {
        String id = subscriptions.ensureSubscription("acct-1", "team-alpha-prod").azureSubscriptionId();
        managementGroups.ensurePlacedUnder(id, "mg-workloads");

        assertThat(managementGroups.ensurePlacedUnder(id, "mg-sandbox")).isEqualTo(ReconcileOutcome.UPDATED);
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
