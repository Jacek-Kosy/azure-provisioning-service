package pl.jacekk.azureprovisioningservice.application.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import pl.jacekk.azureprovisioningservice.config.ProvisioningProperties;
import pl.jacekk.azureprovisioningservice.domain.model.Account;
import pl.jacekk.azureprovisioningservice.domain.port.out.AccountRepositoryPort;
import pl.jacekk.azureprovisioningservice.domain.port.out.ConcurrentAccountModificationException;

import java.time.Clock;
import java.time.Instant;

/**
 * Recovers jobs whose owning replica died mid-run.
 *
 * <p>It deliberately does not resume anything. It marks an abandoned job {@code FAILED}, which
 * makes it eligible for the ordinary retry path — and that path already deletes whatever the dead
 * replica left behind before rerunning from step 1. Without this, a killed replica would park a
 * subscription name in a non-terminal status, and every later request for that name would conflict
 * forever.
 */
@Component
public class StaleJobSweeper {

    private static final Logger log = LoggerFactory.getLogger(StaleJobSweeper.class);

    private final AccountRepositoryPort accounts;
    private final Clock clock;
    private final ProvisioningProperties properties;

    public StaleJobSweeper(AccountRepositoryPort accounts, Clock clock, ProvisioningProperties properties) {
        this.accounts = accounts;
        this.clock = clock;
        this.properties = properties;
    }

    @Scheduled(fixedDelayString = "${provisioning.sweep-interval:PT1M}")
    public void sweep() {
        Instant now = clock.instant();
        for (Account account : accounts.findStale(now, properties.sweepBatchSize())) {
            recover(account, now);
        }
    }

    private void recover(Account account, Instant now) {
        MDC.put(AsyncProvisioningWorkflow.MDC_JOB_ID, account.getJobId());
        MDC.put(AsyncProvisioningWorkflow.MDC_ACCOUNT_ID, account.getId());
        try {
            String abandonedBy = account.getOwnerId();
            account.abandon(now);
            accounts.save(account);
            log.warn("Recovered job for subscription '{}' abandoned by {}; it is now retryable",
                    account.getSubscriptionName(), abandonedBy);
        } catch (ConcurrentAccountModificationException e) {
            log.debug("Account {} was recovered by another replica first", account.getId());
        } catch (RuntimeException e) {
            log.error("Could not recover abandoned account {}", account.getId(), e);
        } finally {
            MDC.remove(AsyncProvisioningWorkflow.MDC_JOB_ID);
            MDC.remove(AsyncProvisioningWorkflow.MDC_ACCOUNT_ID);
        }
    }
}
