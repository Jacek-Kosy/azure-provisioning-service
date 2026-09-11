package pl.jacekk.azureprovisioningservice.domain.port.out;

import pl.jacekk.azureprovisioningservice.domain.model.Account;
import pl.jacekk.azureprovisioningservice.domain.model.JobLease;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * Persistence for the {@code Account} aggregate.
 *
 * <p>Note what is <em>not</em> here: a "read the account, decide, then write it back" pair. With
 * several replicas serving POSTs, that sequence lets two of them both start a run for the same
 * account. Every state change that decides who does the work is therefore a single atomic
 * operation on this port.
 */
public interface AccountRepositoryPort {

    /**
     * Inserts a brand new job.
     *
     * @throws DuplicateSubscriptionNameException when the subscription name is already held
     */
    Account insertNew(Account account);

    Optional<Account> findById(String accountId);

    Optional<Account> findBySubscriptionName(String subscriptionName);

    /**
     * Atomically claims a failed account as a retry: one compare-and-set that only matches while
     * the account is still {@code FAILED}, moving it to {@code CLEANING_UP} under the given lease
     * and clearing the previous error.
     *
     * @return the claimed account, or empty when the account is absent, not failed, or was claimed
     *         by another replica first
     */
    Optional<Account> claimForRetry(String subscriptionName, JobLease lease, Instant now);

    /**
     * Writes an account whose state was advanced in memory.
     *
     * @throws ConcurrentAccountModificationException when another replica wrote it first
     */
    Account save(Account account);

    /** In-flight jobs whose lease has expired — their owning replica is gone. */
    List<Account> findStale(Instant now, int limit);
}
