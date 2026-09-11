package pl.jacekk.azureprovisioningservice.adapter.out.validation;

import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import pl.jacekk.azureprovisioningservice.domain.model.Labels;

import java.util.List;
import java.util.Map;

/**
 * Requires every label beyond the mandatory four to carry a real value.
 *
 * <p>Azure has no concept of a null tag value, and without this a request like
 * {@code {"owner-email": null}} would pass validation and then fail while the labels were turned
 * into a value object — a 500 for what is plainly a bad request. The required keys are left to
 * {@link RequiredKeysPresentRule} so a blank one is reported once, not twice.
 */
@Component
@Order(1)
public class NoBlankLabelValuesRule implements LabelRule {

    @Override
    public List<String> check(Map<String, String> labels) {
        return labels.entrySet().stream()
                .filter(label -> !Labels.REQUIRED_KEYS.contains(label.getKey()))
                .filter(label -> label.getValue() == null || label.getValue().isBlank())
                .map(label -> "label '%s' must not be blank".formatted(label.getKey()))
                .toList();
    }
}
