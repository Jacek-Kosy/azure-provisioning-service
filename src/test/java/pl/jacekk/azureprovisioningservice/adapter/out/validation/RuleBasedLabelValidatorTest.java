package pl.jacekk.azureprovisioningservice.adapter.out.validation;

import org.junit.jupiter.api.Test;
import pl.jacekk.azureprovisioningservice.domain.model.Labels;
import pl.jacekk.azureprovisioningservice.domain.port.out.LabelValidationResult;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class RuleBasedLabelValidatorTest {

    private final RuleBasedLabelValidator validator = new RuleBasedLabelValidator(
            List.of(new RequiredKeysPresentRule(), new NoBlankLabelValuesRule()));

    private static Map<String, String> allFour() {
        Map<String, String> labels = new HashMap<>();
        labels.put(Labels.COST_CENTER_ID, "CC-1001");
        labels.put(Labels.COST_CENTER_ID_PROVIDER, "sap");
        labels.put(Labels.PROJECT_INTERNAL_ID, "PRJ-42");
        labels.put(Labels.PROJECT_INTERNAL_ID_PROVIDER, "servicenow");
        return labels;
    }

    @Test
    void acceptsAllFourRequiredKeys() {
        LabelValidationResult result = validator.validate(allFour());

        assertThat(result.valid()).isTrue();
        assertThat(result.violations()).isEmpty();
    }

    @Test
    void reportsEachMissingKeyByName() {
        Map<String, String> labels = allFour();
        labels.remove(Labels.PROJECT_INTERNAL_ID);
        labels.remove(Labels.COST_CENTER_ID_PROVIDER);

        LabelValidationResult result = validator.validate(labels);

        assertThat(result.valid()).isFalse();
        assertThat(result.violations()).containsExactlyInAnyOrder(
                "label 'cost-center-id-provider' is required",
                "label 'project-internal-id' is required");
    }

    @Test
    void rejectsAValuePresentButBlank() {
        Map<String, String> labels = allFour();
        labels.put(Labels.COST_CENTER_ID, "   ");

        LabelValidationResult result = validator.validate(labels);

        assertThat(result.valid()).isFalse();
        assertThat(result.violations()).containsExactly("label 'cost-center-id' must not be blank");
    }

    @Test
    void rejectsANullValue() {
        Map<String, String> labels = allFour();
        labels.put(Labels.PROJECT_INTERNAL_ID_PROVIDER, null);

        LabelValidationResult result = validator.validate(labels);

        assertThat(result.violations())
                .containsExactly("label 'project-internal-id-provider' must not be blank");
    }

    @Test
    void treatsNoLabelsAtAllAsAllFourMissing() {
        LabelValidationResult result = validator.validate(null);

        assertThat(result.violations()).hasSize(4);
        assertThat(result.violations()).allMatch(violation -> violation.endsWith("is required"));
    }

    @Test
    void toleratesExtraKeysBeyondTheRequiredFour() {
        Map<String, String> labels = allFour();
        labels.put("owner-email", "team-alpha@example.com");

        assertThat(validator.validate(labels).valid()).isTrue();
    }

    @Test
    void rejectsAnExtraKeyWithNoValue() {
        // Azure will not accept a null tag value, and the request must fail as a 400 rather than
        // blowing up while the labels are turned into a value object.
        Map<String, String> labels = allFour();
        labels.put("owner-email", null);

        LabelValidationResult result = validator.validate(labels);

        assertThat(result.violations()).containsExactly("label 'owner-email' must not be blank");
    }

    @Test
    void reportsABlankRequiredKeyExactlyOnce() {
        Map<String, String> labels = allFour();
        labels.put(Labels.COST_CENTER_ID, " ");

        assertThat(validator.validate(labels).violations())
                .containsExactly("label 'cost-center-id' must not be blank");
    }

    @Test
    void collectsViolationsFromEveryConfiguredRuleInOrder() {
        // The seam a provider-specific format or enum check plugs into: another LabelRule bean,
        // with no change to the use case.
        LabelRule costCenterFormat = labels -> labels.get(Labels.COST_CENTER_ID).startsWith("CC-")
                ? List.of()
                : List.of("label 'cost-center-id' must start with CC-");
        RuleBasedLabelValidator composed =
                new RuleBasedLabelValidator(List.of(new RequiredKeysPresentRule(), costCenterFormat));

        Map<String, String> labels = allFour();
        labels.put(Labels.COST_CENTER_ID, "XX-1");
        labels.remove(Labels.PROJECT_INTERNAL_ID);

        LabelValidationResult result = composed.validate(labels);

        assertThat(result.violations()).containsExactly(
                "label 'project-internal-id' is required",
                "label 'cost-center-id' must start with CC-");
    }
}
