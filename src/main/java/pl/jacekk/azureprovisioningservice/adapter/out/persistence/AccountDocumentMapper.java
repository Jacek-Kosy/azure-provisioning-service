package pl.jacekk.azureprovisioningservice.adapter.out.persistence;

import pl.jacekk.azureprovisioningservice.domain.model.Account;
import pl.jacekk.azureprovisioningservice.domain.model.Labels;

import java.util.Map;

/** Translates between the aggregate and its stored form. */
final class AccountDocumentMapper {

    private AccountDocumentMapper() {
    }

    static AccountDocument toDocument(Account account) {
        return AccountDocument.builder()
                .id(account.getId())
                .subscriptionName(account.getSubscriptionName())
                .targetManagementGroup(account.getTargetManagementGroup())
                .labels(Map.copyOf(account.getLabels().asMap()))
                .status(account.getStatus())
                .createdAt(account.getCreatedAt())
                .updatedAt(account.getUpdatedAt())
                .azureSubscriptionId(account.getAzureSubscriptionId())
                .errorDetail(account.getErrorDetail())
                .attempt(account.getAttempt())
                .jobId(account.getJobId())
                .ownerId(account.getOwnerId())
                .leaseExpiresAt(account.getLeaseExpiresAt())
                .version(account.getVersion())
                .build();
    }

    static Account toDomain(AccountDocument document) {
        return Account.restore()
                .id(document.getId())
                .subscriptionName(document.getSubscriptionName())
                .targetManagementGroup(document.getTargetManagementGroup())
                .labels(Labels.of(document.getLabels() == null ? Map.of() : document.getLabels()))
                .status(document.getStatus())
                .createdAt(document.getCreatedAt())
                .updatedAt(document.getUpdatedAt())
                .azureSubscriptionId(document.getAzureSubscriptionId())
                .errorDetail(document.getErrorDetail())
                .attempt(document.getAttempt())
                .jobId(document.getJobId())
                .ownerId(document.getOwnerId())
                .leaseExpiresAt(document.getLeaseExpiresAt())
                .version(document.getVersion())
                .build();
    }
}
