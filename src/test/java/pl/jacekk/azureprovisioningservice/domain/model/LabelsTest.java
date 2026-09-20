package pl.jacekk.azureprovisioningservice.domain.model;

import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class LabelsTest {

    private static Map<String, String> validValues() {
        Map<String, String> values = new HashMap<>();
        values.put(Labels.COST_CENTER_ID, "CC-1001");
        values.put(Labels.COST_CENTER_ID_PROVIDER, "sap");
        values.put(Labels.PROJECT_INTERNAL_ID, "PRJ-42");
        values.put(Labels.PROJECT_INTERNAL_ID_PROVIDER, "servicenow");
        return values;
    }

    @Test
    void exposesTheFourRequiredKeys() {
        assertThat(Labels.REQUIRED_KEYS).containsExactly(
                "cost-center-id",
                "cost-center-id-provider",
                "project-internal-id",
                "project-internal-id-provider");
    }

    @Test
    void copiesTheSourceMapDefensively() {
        Map<String, String> source = validValues();
        Labels labels = Labels.of(source);

        source.put(Labels.COST_CENTER_ID, "tampered");

        assertThat(labels.asMap().get(Labels.COST_CENTER_ID)).isEqualTo("CC-1001");
    }

    @Test
    void exposesAnUnmodifiableView() {
        Labels labels = Labels.of(validValues());

        assertThatThrownBy(() -> labels.asMap().put("extra", "value"))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void rejectsNullValues() {
        assertThatThrownBy(() -> Labels.of(null)).isInstanceOf(NullPointerException.class);
    }

    @Test
    void isEqualByItsValues() {
        assertThat(Labels.of(validValues())).isEqualTo(Labels.of(validValues()));
        assertThat(Labels.of(validValues())).hasSameHashCodeAs(Labels.of(validValues()));
    }

    @Test
    void doesNotItselfRejectIncompleteLabels() {
        // Completeness is LabelValidationPort's job, so that a provider-specific rule can be
        // plugged in without the value object knowing about it.
        assertThat(Labels.of(Map.of(Labels.COST_CENTER_ID, "CC-1001")).asMap()).hasSize(1);
    }
}
