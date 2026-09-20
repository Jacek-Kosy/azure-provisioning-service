package pl.jacekk.azureprovisioningservice.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;
import java.util.UUID;

/**
 * Tuning for the provisioning workflow and its multi-replica coordination.
 *
 * <p>How often abandoned jobs are looked for is {@code provisioning.sweep-interval}, read
 * directly by the sweeper's {@code @Scheduled}.
 *
 * @param leaseDuration how long a replica's claim on a job stays valid without renewal
 * @param sweepBatchSize how many abandoned jobs one sweep recovers
 * @param ownerId identifies this replica in claims and in the errorDetail of jobs it abandons
 */
@ConfigurationProperties(prefix = "provisioning")
public record ProvisioningProperties(Duration leaseDuration, int sweepBatchSize, String ownerId) {

    public ProvisioningProperties {
        leaseDuration = leaseDuration == null ? Duration.ofMinutes(5) : leaseDuration;
        sweepBatchSize = sweepBatchSize <= 0 ? 100 : sweepBatchSize;
        ownerId = ownerId == null || ownerId.isBlank() ? UUID.randomUUID().toString() : ownerId;
    }
}
