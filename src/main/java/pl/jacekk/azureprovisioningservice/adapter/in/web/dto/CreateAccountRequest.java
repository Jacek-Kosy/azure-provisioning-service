package pl.jacekk.azureprovisioningservice.adapter.in.web.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;

import java.util.Map;

/**
 * A provisioning request.
 *
 * <p>{@code labels} is intentionally not bean-validated: the four required keys are checked by
 * {@code LabelValidationPort}, so the rules live in one pluggable place rather than being split
 * between annotations here and a port implementation there.
 */
public record CreateAccountRequest(

        @Schema(description = "Idempotency key: a second request for the same name is a conflict, "
                + "or a retry when the previous job failed", example = "team-alpha-prod")
        @NotBlank(message = "subscriptionName must not be blank")
        String subscriptionName,

        @Schema(description = "Management group the new subscription is moved into",
                example = "mg-workloads")
        @NotBlank(message = "targetManagementGroup must not be blank")
        String targetManagementGroup,

        @Schema(description = "Must contain exactly the four required keys, each non-blank")
        Map<String, String> labels) {
}
