package pl.jacekk.azureprovisioningservice.adapter.out.persistence;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.FindAndModifyOptions;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.stereotype.Component;
import pl.jacekk.azureprovisioningservice.domain.model.Account;
import pl.jacekk.azureprovisioningservice.domain.model.JobLease;
import pl.jacekk.azureprovisioningservice.domain.model.ProvisioningStatus;
import pl.jacekk.azureprovisioningservice.domain.port.out.AccountRepositoryPort;
import pl.jacekk.azureprovisioningservice.domain.port.out.ConcurrentAccountModificationException;
import pl.jacekk.azureprovisioningservice.domain.port.out.DuplicateSubscriptionNameException;

import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;

@Component
public class MongoAccountRepositoryAdapter implements AccountRepositoryPort {

    private static final List<ProvisioningStatus> IN_FLIGHT_STATUSES =
            Arrays.stream(ProvisioningStatus.values()).filter(ProvisioningStatus::isInFlight).toList();

    private final SpringDataAccountRepository repository;
    private final MongoTemplate mongoTemplate;

    public MongoAccountRepositoryAdapter(SpringDataAccountRepository repository, MongoTemplate mongoTemplate) {
        this.repository = repository;
        this.mongoTemplate = mongoTemplate;
    }

    @Override
    public Account insertNew(Account account) {
        try {
            AccountDocument inserted = repository.insert(AccountDocumentMapper.toDocument(account));
            account.applyPersistedVersion(inserted.getVersion());
            return account;
        } catch (DuplicateKeyException e) {
            // The only unique constraint on the collection is the subscription name.
            throw new DuplicateSubscriptionNameException(account.getSubscriptionName());
        }
    }

    @Override
    public Optional<Account> findById(String accountId) {
        return repository.findById(accountId).map(AccountDocumentMapper::toDomain);
    }

    @Override
    public Optional<Account> findBySubscriptionName(String subscriptionName) {
        return repository.findBySubscriptionName(subscriptionName).map(AccountDocumentMapper::toDomain);
    }

    /**
     * One conditional update, not a read followed by a write: the filter itself requires the
     * account to still be FAILED, so when several replicas accept the same retry at the same
     * moment, exactly one of them gets a document back and starts the run.
     */
    @Override
    public Optional<Account> claimForRetry(String subscriptionName, JobLease lease, Instant now) {
        Query onlyWhileFailed = Query.query(Criteria
                .where("subscriptionName").is(subscriptionName)
                .and("status").is(ProvisioningStatus.FAILED));
        Update claim = new Update()
                .set("status", ProvisioningStatus.CLEANING_UP)
                .set("jobId", lease.jobId())
                .set("ownerId", lease.ownerId())
                .set("leaseExpiresAt", lease.expiresAt())
                .set("updatedAt", now)
                .unset("errorDetail")
                // findAndModify bypasses Spring Data's version handling, so bump it here: a claim
                // is a write, and any copy read before it must now be rejected.
                .inc("version", 1);

        AccountDocument claimed = mongoTemplate.findAndModify(
                onlyWhileFailed, claim, FindAndModifyOptions.options().returnNew(true), AccountDocument.class);
        return Optional.ofNullable(claimed).map(AccountDocumentMapper::toDomain);
    }

    @Override
    public Account save(Account account) {
        try {
            AccountDocument saved = repository.save(AccountDocumentMapper.toDocument(account));
            account.applyPersistedVersion(saved.getVersion());
            return account;
        } catch (OptimisticLockingFailureException e) {
            throw new ConcurrentAccountModificationException(account.getId());
        }
    }

    @Override
    public List<Account> findStale(Instant now, int limit) {
        Query expiredLeases = Query.query(Criteria
                        .where("status").in(IN_FLIGHT_STATUSES)
                        .and("leaseExpiresAt").lte(now))
                .with(Sort.by(Sort.Direction.ASC, "leaseExpiresAt"))
                .limit(limit);
        return mongoTemplate.find(expiredLeases, AccountDocument.class).stream()
                .map(AccountDocumentMapper::toDomain)
                .toList();
    }
}
