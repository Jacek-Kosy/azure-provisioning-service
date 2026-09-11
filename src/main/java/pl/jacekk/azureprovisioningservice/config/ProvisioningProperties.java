package pl.jacekk.azureprovisioningservice.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.time.Duration;
import java.util.UUID;

/**
 * Tuning for the provisioning workflow and its multi-replica coordination.
 *
 * @param leaseDuration how long a replica's claim on a job stays valid without renewal
 * @param sweepInterval how often abandoned jobs are looked for
 * @param sweepBatchSize how many abandoned jobs one sweep recovers
 * @param ownerId identifies this replica in claims and in the errorDetail of jobs it abandons;
 *                derived from the hostname when the deployment does not set it
 */
@ConfigurationProperties(prefix = "provisioning")
public record ProvisioningProperties(Duration leaseDuration,
                                     Duration sweepInterval,
                                     int sweepBatchSize,
                                     String ownerId) {

    public ProvisioningProperties {
        leaseDuration = leaseDuration == null ? Duration.ofMinutes(5) : leaseDuration;
        sweepInterval = sweepInterval == null ? Duration.ofMinutes(1) : sweepInterval;
        sweepBatchSize = sweepBatchSize <= 0 ? 100 : sweepBatchSize;
        ownerId = ownerId == null || ownerId.isBlank() ? derivedOwnerId() : ownerId;
    }

    private static String derivedOwnerId() {
        String host = System.getenv("HOSTNAME");
        if (host == null || host.isBlank()) {
            try {
                host = InetAddress.getLocalHost().getHostName();
            } catch (UnknownHostException e) {
                host = "unknown-host";
            }
        }
        // Two replicas can share a host, so the process gets its own suffix.
        return host + "-" + UUID.randomUUID().toString().substring(0, 8);
    }
}
