package pl.jacekk.azureprovisioningservice.adapter.out.azure;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import pl.jacekk.azureprovisioningservice.config.AzureProperties;
import pl.jacekk.azureprovisioningservice.domain.model.Labels;
import pl.jacekk.azureprovisioningservice.domain.port.out.AzureSubscriptionPort;

import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * STUB. Interface-correct, but nothing reaches Azure.
 *
 * <p>TODO: implement with {@code com.azure.resourcemanager.subscription.SubscriptionManager},
 * authenticated with the injected {@code TokenCredential}. Creating a subscription means creating
 * an <em>alias</em> ({@code Microsoft.Subscription/aliases/{aliasName}}) against a billing scope —
 * an EA enrollment account, MCA billing profile or MPA agreement — which is why this is a stub: the
 * billing-scope wiring and the entitlements that go with it are an enterprise-agreement concern,
 * not a code concern. {@code deleteSubscription} maps to cancelling the subscription, after which
 * Azure keeps it recoverable for a grace period.
 *
 * <p>The alias map below stands in for Azure's alias registry so the recovery contract is actually
 * exercised by tests. It is per-process and in-memory, which a real adapter obviously is not —
 * <strong>verify against the live API</strong> that looking an alias up returns its subscription
 * and that re-creating an existing alias is idempotent, because the leak-recovery path in
 * {@code AsyncProvisioningWorkflow} depends on both.
 */
@Component
public class StubAzureSubscriptionAdapter implements AzureSubscriptionPort {

    private static final Logger log = LoggerFactory.getLogger(StubAzureSubscriptionAdapter.class);

    private final Map<String, String> subscriptionIdsByAlias = new ConcurrentHashMap<>();
    private final AzureProperties properties;

    public StubAzureSubscriptionAdapter(AzureProperties properties) {
        this.properties = properties;
    }

    @Override
    public String createSubscription(String alias, String subscriptionName) {
        String azureSubscriptionId = subscriptionIdsByAlias.computeIfAbsent(
                alias, ignored -> UUID.randomUUID().toString());
        log.warn("STUB: not creating subscription '{}' in Azure; alias {} stands for {} "
                        + "(billing scope would be '{}')",
                subscriptionName, alias, azureSubscriptionId, properties.billingScope());
        return azureSubscriptionId;
    }

    @Override
    public Optional<String> findSubscriptionIdByAlias(String alias) {
        return Optional.ofNullable(subscriptionIdsByAlias.get(alias));
    }

    @Override
    public void applyTags(String azureSubscriptionId, Labels labels) {
        log.warn("STUB: not tagging subscription {} with {} keys", azureSubscriptionId, labels.asMap().size());
    }

    @Override
    public void deleteSubscription(String azureSubscriptionId) {
        boolean known = subscriptionIdsByAlias.values().remove(azureSubscriptionId);
        log.warn("STUB: not deleting subscription {} left behind by a failed run (known here: {})",
                azureSubscriptionId, known);
    }
}
