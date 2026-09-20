package pl.jacekk.azureprovisioningservice.domain.model;

import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Immutable value object over the tags applied to a provisioned subscription.
 *
 * <p>It knows the names of the four required keys but deliberately does not enforce them —
 * that is {@code LabelValidationPort}'s job, so a provider-specific format or enum check can be
 * plugged in later without touching either this type or the use case.
 */
public final class Labels {

    public static final String COST_CENTER_ID = "cost-center-id";
    public static final String COST_CENTER_ID_PROVIDER = "cost-center-id-provider";
    public static final String PROJECT_INTERNAL_ID = "project-internal-id";
    public static final String PROJECT_INTERNAL_ID_PROVIDER = "project-internal-id-provider";

    public static final List<String> REQUIRED_KEYS = List.of(
            COST_CENTER_ID,
            COST_CENTER_ID_PROVIDER,
            PROJECT_INTERNAL_ID,
            PROJECT_INTERNAL_ID_PROVIDER);

    private final Map<String, String> values;

    private Labels(Map<String, String> values) {
        this.values = values;
    }

    public static Labels of(Map<String, String> values) {
        Objects.requireNonNull(values, "labels must not be null");
        return new Labels(Map.copyOf(values));
    }

    public Map<String, String> asMap() {
        return values;
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof Labels labels && values.equals(labels.values);
    }

    @Override
    public int hashCode() {
        return values.hashCode();
    }

    @Override
    public String toString() {
        return "Labels" + values;
    }
}
