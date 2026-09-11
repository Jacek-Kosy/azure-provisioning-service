package pl.jacekk.azureprovisioningservice.domain.model;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import static org.assertj.core.api.Assertions.assertThat;
import static pl.jacekk.azureprovisioningservice.domain.model.ProvisioningStatus.APPLYING_LABELS;
import static pl.jacekk.azureprovisioningservice.domain.model.ProvisioningStatus.ASSIGNING_MANAGEMENT_GROUP;
import static pl.jacekk.azureprovisioningservice.domain.model.ProvisioningStatus.CLEANING_UP;
import static pl.jacekk.azureprovisioningservice.domain.model.ProvisioningStatus.COMPLETED;
import static pl.jacekk.azureprovisioningservice.domain.model.ProvisioningStatus.CREATING_SUBSCRIPTION;
import static pl.jacekk.azureprovisioningservice.domain.model.ProvisioningStatus.FAILED;
import static pl.jacekk.azureprovisioningservice.domain.model.ProvisioningStatus.PENDING;

class ProvisioningStatusTest {

    @Test
    void onlyCompletedAndFailedAreTerminal() {
        assertThat(COMPLETED.isTerminal()).isTrue();
        assertThat(FAILED.isTerminal()).isTrue();
        assertThat(PENDING.isTerminal()).isFalse();
        assertThat(CLEANING_UP.isTerminal()).isFalse();
        assertThat(CREATING_SUBSCRIPTION.isTerminal()).isFalse();
    }

    @Test
    void onlyFailedIsRetryable() {
        assertThat(FAILED.isRetryable()).isTrue();
        assertThat(COMPLETED.isRetryable()).isFalse();
        assertThat(PENDING.isRetryable()).isFalse();
    }

    @ParameterizedTest
    @EnumSource(ProvisioningStatus.class)
    void inFlightIsTheComplementOfTerminal(ProvisioningStatus status) {
        assertThat(status.isInFlight()).isEqualTo(!status.isTerminal());
    }

    @Test
    void allowsTheProvisioningPath() {
        assertThat(PENDING.canTransitionTo(CREATING_SUBSCRIPTION)).isTrue();
        assertThat(CREATING_SUBSCRIPTION.canTransitionTo(ASSIGNING_MANAGEMENT_GROUP)).isTrue();
        assertThat(ASSIGNING_MANAGEMENT_GROUP.canTransitionTo(APPLYING_LABELS)).isTrue();
        assertThat(APPLYING_LABELS.canTransitionTo(COMPLETED)).isTrue();
    }

    @Test
    void allowsRetryClaimAndCleanupOutcomes() {
        assertThat(FAILED.canTransitionTo(CLEANING_UP)).isTrue();
        assertThat(CLEANING_UP.canTransitionTo(CREATING_SUBSCRIPTION)).isTrue();
        assertThat(CLEANING_UP.canTransitionTo(FAILED)).isTrue();
    }

    @Test
    void allowsFailureFromEveryInFlightStatus() {
        assertThat(PENDING.canTransitionTo(FAILED)).isTrue();
        assertThat(CREATING_SUBSCRIPTION.canTransitionTo(FAILED)).isTrue();
        assertThat(ASSIGNING_MANAGEMENT_GROUP.canTransitionTo(FAILED)).isTrue();
        assertThat(APPLYING_LABELS.canTransitionTo(FAILED)).isTrue();
    }

    @Test
    void forbidsStepSkippingAndResurrection() {
        assertThat(PENDING.canTransitionTo(APPLYING_LABELS)).isFalse();
        assertThat(CREATING_SUBSCRIPTION.canTransitionTo(COMPLETED)).isFalse();
        assertThat(COMPLETED.canTransitionTo(CLEANING_UP)).isFalse();
        assertThat(COMPLETED.canTransitionTo(FAILED)).isFalse();
        assertThat(FAILED.canTransitionTo(CREATING_SUBSCRIPTION)).isFalse();
    }
}
