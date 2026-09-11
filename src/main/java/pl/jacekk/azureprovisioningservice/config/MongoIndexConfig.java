package pl.jacekk.azureprovisioningservice.config;

import jakarta.annotation.PostConstruct;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.index.Index;
import pl.jacekk.azureprovisioningservice.adapter.out.persistence.AccountDocument;

/**
 * Creates the collection's indexes explicitly rather than relying on {@code auto-index-creation}.
 *
 * <p>The unique index is not a nicety: it is what makes the subscription name a real idempotency
 * key when several replicas accept requests at once. Creation is deliberately eager and fatal — a
 * service that started without it would silently accept duplicate provisioning jobs.
 */
@Configuration
public class MongoIndexConfig {

    private final MongoTemplate mongoTemplate;

    public MongoIndexConfig(MongoTemplate mongoTemplate) {
        this.mongoTemplate = mongoTemplate;
    }

    @PostConstruct
    void createAccountIndexes() {
        mongoTemplate.indexOps(AccountDocument.class).createIndex(
                new Index().on("subscriptionName", Sort.Direction.ASC)
                        .unique()
                        .named("uk_accounts_subscription_name"));

        // Supports the stale-job sweeper's query.
        mongoTemplate.indexOps(AccountDocument.class).createIndex(
                new Index().on("status", Sort.Direction.ASC)
                        .on("leaseExpiresAt", Sort.Direction.ASC)
                        .named("ix_accounts_status_lease_expires_at"));
    }
}
