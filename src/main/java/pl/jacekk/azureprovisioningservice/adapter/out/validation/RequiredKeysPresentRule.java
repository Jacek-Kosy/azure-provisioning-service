package pl.jacekk.azureprovisioningservice.adapter.out.validation;

import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import pl.jacekk.azureprovisioningservice.domain.model.Labels;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** Requires each of the four mandatory keys to be present with a non-blank value. */
@Component
@Order(0)
public class RequiredKeysPresentRule implements LabelRule {

    @Override
    public List<String> check(Map<String, String> labels) {
        List<String> violations = new ArrayList<>();
        for (String key : Labels.REQUIRED_KEYS) {
            if (!labels.containsKey(key)) {
                violations.add("label '%s' is required".formatted(key));
                continue;
            }
            String value = labels.get(key);
            if (value == null || value.isBlank()) {
                violations.add("label '%s' must not be blank".formatted(key));
            }
        }
        return violations;
    }
}
