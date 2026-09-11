package pl.jacekk.azureprovisioningservice.domain.port.out;

import java.util.List;

/** Outcome of validating a request's labels: empty violations means accepted. */
public record LabelValidationResult(List<String> violations) {

    public LabelValidationResult {
        violations = List.copyOf(violations);
    }

    public static LabelValidationResult accepted() {
        return new LabelValidationResult(List.of());
    }

    public static LabelValidationResult of(List<String> violations) {
        return new LabelValidationResult(violations);
    }

    public boolean valid() {
        return violations.isEmpty();
    }
}
