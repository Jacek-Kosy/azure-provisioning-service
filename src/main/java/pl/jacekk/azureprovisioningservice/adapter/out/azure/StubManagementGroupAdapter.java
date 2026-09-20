package pl.jacekk.azureprovisioningservice.adapter.out.azure;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import pl.jacekk.azureprovisioningservice.config.AzureProperties;
import pl.jacekk.azureprovisioningservice.domain.port.out.ManagementGroupPort;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * STUB. Interface-correct, but nothing reaches Azure.
 *
 * <p>TODO: implement with
 * {@code com.azure.resourcemanager.managementgroups.ManagementGroupsManager}. Read the
 * subscription's current parent, and only call
 * {@code managementGroupSubscriptions().create(groupId, subscriptionId)} when it differs. The
 * caller needs write access on the target group and ownership of the subscription.
 */
@Component
public class StubManagementGroupAdapter implements ManagementGroupPort {

    private static final Logger log = LoggerFactory.getLogger(StubManagementGroupAdapter.class);

    private final Map<String, String> parentBySubscription = new ConcurrentHashMap<>();
    private final AzureProperties properties;

    public StubManagementGroupAdapter(AzureProperties properties) {
        this.properties = properties;
    }

    @Override
    public void ensurePlacedUnder(String azureSubscriptionId, String managementGroupId) {
        String target = managementGroupId == null || managementGroupId.isBlank()
                ? properties.rootManagementGroup()
                : managementGroupId;
        if (!target.equals(parentBySubscription.put(azureSubscriptionId, target))) {
            log.warn("STUB: not moving subscription {} into management group {}", azureSubscriptionId, target);
        }
    }
}
