package pl.jacekk.azureprovisioningservice.adapter.out.azure;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import pl.jacekk.azureprovisioningservice.config.AzureProperties;
import pl.jacekk.azureprovisioningservice.domain.model.Labels;
import pl.jacekk.azureprovisioningservice.domain.port.out.AzureSubscriptionPort;

import java.util.UUID;

/**
 * STUB. Interface-correct, but nothing reaches Azure.
 *
 * <p>TODO: implement with {@code com.azure.resourcemanager.subscription.SubscriptionManager},
 * authenticated with the injected {@code TokenCredential}. Creating a subscription means creating
 * an <em>alias</em> against a billing scope (an EA enrollment account, MCA billing profile or MPA
 * agreement), which is why this is a stub: the billing-scope wiring and the entitlements that go
 * with it are an enterprise-agreement concern, not a code concern. {@code deleteSubscription} maps
 * to cancelling the subscription, after which Azure keeps it recoverable for a grace period.
 *
 * <p>Everything around these calls — validation, idempotency, the state machine and the
 * retry-then-rerun workflow — is real, so replacing the bodies below is the whole job.
 */
@Component
public class StubAzureSubscriptionAdapter implements AzureSubscriptionPort {

    private static final Logger log = LoggerFactory.getLogger(StubAzureSubscriptionAdapter.class);

    private final AzureProperties properties;

    public StubAzureSubscriptionAdapter(AzureProperties properties) {
        this.properties = properties;
    }

    @Override
    public String createSubscription(String subscriptionName) {
        String azureSubscriptionId = UUID.randomUUID().toString();
        log.warn("STUB: not creating subscription '{}' in Azure; pretending it became {} "
                        + "(billing scope would be '{}')",
                subscriptionName, azureSubscriptionId, properties.billingScope());
        return azureSubscriptionId;
    }

    @Override
    public void applyTags(String azureSubscriptionId, Labels labels) {
        log.warn("STUB: not tagging subscription {} with {} keys", azureSubscriptionId, labels.asMap().size());
    }

    @Override
    public void deleteSubscription(String azureSubscriptionId) {
        log.warn("STUB: not deleting subscription {} left behind by a failed run", azureSubscriptionId);
    }
}
