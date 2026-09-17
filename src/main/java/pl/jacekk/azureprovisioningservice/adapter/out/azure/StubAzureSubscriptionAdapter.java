package pl.jacekk.azureprovisioningservice.adapter.out.azure;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import pl.jacekk.azureprovisioningservice.config.AzureProperties;
import pl.jacekk.azureprovisioningservice.domain.model.Labels;
import pl.jacekk.azureprovisioningservice.domain.port.out.AzureSubscriptionPort;
import pl.jacekk.azureprovisioningservice.domain.port.out.ProvisionedSubscription;
import pl.jacekk.azureprovisioningservice.domain.port.out.ReconcileOutcome;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * STUB. Interface-correct, but nothing reaches Azure.
 *
 * <p>TODO: implement with {@code com.azure.resourcemanager.subscription.SubscriptionManager},
 * authenticated with the injected {@code TokenCredential}. A subscription is created by PUTting an
 * <em>alias</em> ({@code Microsoft.Subscription/aliases/{aliasName}}) against a billing scope — an
 * EA enrollment account, MCA billing profile or MPA agreement — which is why this ships as a stub:
 * the billing-scope wiring is an enterprise-agreement concern, not a code concern.
 *
 * <p>The maps below stand in for Azure so the reconcile contract is actually exercised by tests.
 * <strong>Verify against the live API</strong> that GETting an alias returns its subscription and
 * that PUTting an existing alias is idempotent — the adopt path depends on both.
 */
@Component
public class StubAzureSubscriptionAdapter implements AzureSubscriptionPort {

    private static final Logger log = LoggerFactory.getLogger(StubAzureSubscriptionAdapter.class);

    private final Map<String, String> subscriptionIdsByAlias = new ConcurrentHashMap<>();
    private final Map<String, Map<String, String>> tagsBySubscription = new ConcurrentHashMap<>();
    private final AzureProperties properties;

    public StubAzureSubscriptionAdapter(AzureProperties properties) {
        this.properties = properties;
    }

    @Override
    public ProvisionedSubscription ensureSubscription(String alias, String subscriptionName) {
        String existing = subscriptionIdsByAlias.get(alias);
        if (existing != null) {
            log.warn("STUB: adopting subscription {} already created under alias {}", existing, alias);
            return new ProvisionedSubscription(existing, ReconcileOutcome.ADOPTED);
        }
        String created = UUID.randomUUID().toString();
        subscriptionIdsByAlias.put(alias, created);
        log.warn("STUB: not creating subscription '{}' in Azure; alias {} stands for {} "
                + "(billing scope would be '{}')", subscriptionName, alias, created, properties.billingScope());
        return new ProvisionedSubscription(created, ReconcileOutcome.CREATED);
    }

    @Override
    public ReconcileOutcome ensureTags(String azureSubscriptionId, Labels labels) {
        Map<String, String> current = tagsBySubscription.get(azureSubscriptionId);
        if (labels.asMap().equals(current)) {
            return ReconcileOutcome.ALREADY_SATISFIED;
        }
        tagsBySubscription.put(azureSubscriptionId, labels.asMap());
        log.warn("STUB: not tagging subscription {} with {} keys", azureSubscriptionId, labels.asMap().size());
        return current == null ? ReconcileOutcome.CREATED : ReconcileOutcome.UPDATED;
    }
}
