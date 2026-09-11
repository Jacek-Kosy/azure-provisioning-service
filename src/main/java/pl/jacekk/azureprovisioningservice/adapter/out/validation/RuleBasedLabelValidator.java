package pl.jacekk.azureprovisioningservice.adapter.out.validation;

import org.springframework.stereotype.Component;
import pl.jacekk.azureprovisioningservice.domain.port.out.LabelValidationPort;
import pl.jacekk.azureprovisioningservice.domain.port.out.LabelValidationResult;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** Applies every configured {@link LabelRule} in order and reports all violations together. */
@Component
public class RuleBasedLabelValidator implements LabelValidationPort {

    private final List<LabelRule> rules;

    public RuleBasedLabelValidator(List<LabelRule> rules) {
        this.rules = List.copyOf(rules);
    }

    @Override
    public LabelValidationResult validate(Map<String, String> labels) {
        Map<String, String> present = labels == null ? Map.of() : labels;
        List<String> violations = new ArrayList<>();
        for (LabelRule rule : rules) {
            violations.addAll(rule.check(present));
        }
        return LabelValidationResult.of(violations);
    }
}
