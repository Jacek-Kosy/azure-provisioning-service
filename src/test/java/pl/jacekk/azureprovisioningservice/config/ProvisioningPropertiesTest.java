package pl.jacekk.azureprovisioningservice.config;

import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

class ProvisioningPropertiesTest {

    @Test
    void fallsBackToWorkableDefaults() {
        ProvisioningProperties properties = new ProvisioningProperties(null, 0, null);

        assertThat(properties.leaseDuration()).isEqualTo(Duration.ofMinutes(5));
        assertThat(properties.sweepBatchSize()).isEqualTo(100);
    }

    @Test
    void givesEachReplicaAnOwnerIdWhenTheDeploymentDoesNotConfigureOne() {
        assertThat(new ProvisioningProperties(null, 0, null).ownerId()).isNotBlank();
        assertThat(new ProvisioningProperties(null, 0, "   ").ownerId())
                .isNotEqualTo(new ProvisioningProperties(null, 0, null).ownerId());
    }

    @Test
    void keepsAnExplicitlyConfiguredOwnerId() {
        ProvisioningProperties properties = new ProvisioningProperties(Duration.ofMinutes(2), 10, "pod-7");

        assertThat(properties.ownerId()).isEqualTo("pod-7");
        assertThat(properties.leaseDuration()).isEqualTo(Duration.ofMinutes(2));
        assertThat(properties.sweepBatchSize()).isEqualTo(10);
    }
}
