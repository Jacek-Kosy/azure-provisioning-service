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

    private final AzureProperties properties = new AzureProperties("tenant-1", "/billing/scope", "mg-root");
    private final AzureSubscriptionPort subscriptions = new StubAzureSubscriptionAdapter(properties);
    private final ManagementGroupPort managementGroups = new StubManagementGroupAdapter(properties);

    @Test
    void handsBackAnIdentifierTheWorkflowCanRecord() {
        String azureSubscriptionId = subscriptions.createSubscription("acct-1-1", "team-alpha-prod");

        assertThat(azureSubscriptionId).isNotBlank();
    }

    @Test
    void handsBackADistinctIdentifierPerSubscription() {
        assertThat(subscriptions.createSubscription("acct-1-1", "team-alpha-prod"))
                .isNotEqualTo(subscriptions.createSubscription("acct-2-1", "team-beta-prod"));
    }

    @Test
    void acceptsTagsAndDeletionWithoutFailingTheWorkflow() {
        String azureSubscriptionId = subscriptions.createSubscription("acct-1-1", "team-alpha-prod");

        assertThatCode(() -> subscriptions.applyTags(azureSubscriptionId, LABELS)).doesNotThrowAnyException();
        assertThatCode(() -> managementGroups.assignSubscription(azureSubscriptionId, "mg-workloads"))
                .doesNotThrowAnyException();
        assertThatCode(() -> subscriptions.deleteSubscription(azureSubscriptionId)).doesNotThrowAnyException();
    }

    @Test
    void findsACreatedSubscriptionByTheAliasItWasCreatedUnder() {
        String azureSubscriptionId = subscriptions.createSubscription("acct-1-1", "team-alpha-prod");

        assertThat(subscriptions.findSubscriptionIdByAlias("acct-1-1")).contains(azureSubscriptionId);
    }

    @Test
    void hasNothingForAnAliasThatWasNeverUsed() {
        assertThat(subscriptions.findSubscriptionIdByAlias("acct-never-1")).isEmpty();
    }

    @Test
    void forgetsTheAliasOnceItsSubscriptionIsDeleted() {
        String azureSubscriptionId = subscriptions.createSubscription("acct-1-1", "team-alpha-prod");

        subscriptions.deleteSubscription(azureSubscriptionId);

        assertThat(subscriptions.findSubscriptionIdByAlias("acct-1-1")).isEmpty();
    }

    @Test
    void toleratesDeletingASubscriptionThatIsAlreadyGone() {
        // Cleanup can legitimately run twice on the same subscription, so the port contract is that
        // deletion is idempotent.
        String azureSubscriptionId = subscriptions.createSubscription("acct-1-1", "team-alpha-prod");
        subscriptions.deleteSubscription(azureSubscriptionId);

        assertThatCode(() -> subscriptions.deleteSubscription(azureSubscriptionId))
                .doesNotThrowAnyException();
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
