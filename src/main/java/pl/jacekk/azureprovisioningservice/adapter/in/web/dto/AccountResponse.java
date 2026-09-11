package pl.jacekk.azureprovisioningservice.adapter.in.web.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import pl.jacekk.azureprovisioningservice.domain.model.Account;
import pl.jacekk.azureprovisioningservice.domain.model.ProvisioningStatus;

import java.time.Instant;
import java.util.Map;

/** The state of one provisioning job. Fields that do not apply yet are omitted. */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record AccountResponse(String id,
                              String subscriptionName,
                              String targetManagementGroup,
                              ProvisioningStatus status,
                              String azureSubscriptionId,
                              Map<String, String> labels,
                              String errorDetail,
                              String jobId,
                              Instant createdAt,
                              Instant updatedAt) {

    public static AccountResponse from(Account account) {
        return new AccountResponse(
                account.getId(),
                account.getSubscriptionName(),
                account.getTargetManagementGroup(),
                account.getStatus(),
                account.getAzureSubscriptionId(),
                account.getLabels().asMap(),
                account.getErrorDetail(),
                account.getJobId(),
                account.getCreatedAt(),
                account.getUpdatedAt());
    }
}
