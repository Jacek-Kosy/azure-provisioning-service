package pl.jacekk.azureprovisioningservice.domain.model;

import java.util.List;

/** Raised before anything is persisted when a request's labels fail validation. */
public class InvalidLabelsException extends RuntimeException {

    private final List<String> violations;

    public InvalidLabelsException(List<String> violations) {
        super("Invalid labels: " + String.join("; ", violations));
        this.violations = List.copyOf(violations);
    }

    public List<String> getViolations() {
        return violations;
    }
}
