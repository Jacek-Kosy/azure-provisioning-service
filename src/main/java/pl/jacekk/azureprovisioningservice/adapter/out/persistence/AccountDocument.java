package pl.jacekk.azureprovisioningservice.adapter.out.persistence;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.springframework.data.annotation.Id;
import org.springframework.data.annotation.Version;
import org.springframework.data.mongodb.core.mapping.Document;
import pl.jacekk.azureprovisioningservice.domain.model.ProvisioningStatus;

import java.time.Instant;
import java.util.Map;

/**
 * Mongo representation of the {@code Account} aggregate.
 *
 * <p>The unique index on {@code subscriptionName} — the idempotency key — and the sweeper's index
 * are created explicitly by {@code MongoIndexConfig} rather than by annotation, so the production
 * code path and the integration test exercise the same index definitions.
 */
@Document(collection = "accounts")
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AccountDocument {

    @Id
    private String id;

    private String subscriptionName;
    private String targetManagementGroup;
    private Map<String, String> labels;
    private ProvisioningStatus status;
    private Instant createdAt;
    private Instant updatedAt;
    private String azureSubscriptionId;
    private String errorDetail;

    /** Which provisioning attempt this is; part of the alias the cleanup path reconstructs. */
    private int attempt;

    private String jobId;
    private String ownerId;
    private Instant leaseExpiresAt;

    /** Guards every update against a write from another replica. */
    @Version
    private Long version;
}
