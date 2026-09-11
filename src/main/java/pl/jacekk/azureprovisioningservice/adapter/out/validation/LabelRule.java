package pl.jacekk.azureprovisioningservice.adapter.out.validation;

import java.util.List;
import java.util.Map;

/**
 * One check applied to a request's labels.
 *
 * <p>This is the extension seam: to add a provider-specific format or enum check, publish another
 * {@code LabelRule} bean (ordered with {@code @Order} if it matters). Neither
 * {@link RuleBasedLabelValidator} nor the use case changes.
 */
@FunctionalInterface
public interface LabelRule {

    /** @return one message per violation, empty when the rule is satisfied. */
    List<String> check(Map<String, String> labels);
}
