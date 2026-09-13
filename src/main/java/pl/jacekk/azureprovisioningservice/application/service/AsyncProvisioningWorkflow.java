package pl.jacekk.azureprovisioningservice.application.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import pl.jacekk.azureprovisioningservice.config.ProvisioningProperties;
import pl.jacekk.azureprovisioningservice.domain.model.Account;
import pl.jacekk.azureprovisioningservice.domain.port.out.AccountRepositoryPort;
import pl.jacekk.azureprovisioningservice.domain.port.out.AzureSubscriptionPort;
import pl.jacekk.azureprovisioningservice.domain.port.out.ConcurrentAccountModificationException;
import pl.jacekk.azureprovisioningservice.domain.port.out.ManagementGroupPort;

import java.time.Clock;
import java.time.Instant;
import java.util.Optional;

/**
 * Orchestrates the provisioning run. Every state change goes through the aggregate, so an
 * out-of-order step is rejected by the domain rather than written to Mongo.
 */
@Component
public class AsyncProvisioningWorkflow implements ProvisioningWorkflow {

    private static final Logger log = LoggerFactory.getLogger(AsyncProvisioningWorkflow.class);

    static final String MDC_JOB_ID = "jobId";
    static final String MDC_ACCOUNT_ID = "accountId";

    private final AccountRepositoryPort accounts;
    private final AzureSubscriptionPort subscriptions;
    private final ManagementGroupPort managementGroups;
    private final Clock clock;
    private final ProvisioningProperties properties;

    public AsyncProvisioningWorkflow(AccountRepositoryPort accounts,
                                     AzureSubscriptionPort subscriptions,
                                     ManagementGroupPort managementGroups,
                                     Clock clock,
                                     ProvisioningProperties properties) {
        this.accounts = accounts;
        this.subscriptions = subscriptions;
        this.managementGroups = managementGroups;
        this.clock = clock;
        this.properties = properties;
    }

    @Override
    @Async("provisioningExecutor")
    public void runFresh(String accountId) {
        run(accountId, false);
    }

    @Override
    @Async("provisioningExecutor")
    public void runRetry(String accountId) {
        run(accountId, true);
    }

    private void run(String accountId, boolean retry) {
        Optional<Account> found = accounts.findById(accountId);
        if (found.isEmpty()) {
            log.warn("Provisioning run skipped, account {} no longer exists", accountId);
            return;
        }
        Account account = found.get();
        MDC.put(MDC_JOB_ID, account.getJobId());
        MDC.put(MDC_ACCOUNT_ID, account.getId());
        try {
            log.info("Provisioning run started for subscription '{}' (retry={})",
                    account.getSubscriptionName(), retry);
            if (retry && !cleanUp(account)) {
                return;
            }
            provision(account);
        } finally {
            MDC.remove(MDC_JOB_ID);
            MDC.remove(MDC_ACCOUNT_ID);
        }
    }

    /**
     * Step 0. Deletes whatever the previous run left behind. Returns false when cleanup itself
     * failed — the run then stops, and step 1 is not attempted until a later retry cleans up.
     */
    private boolean cleanUp(Account account) {
        try {
            String orphan = orphanOf(account);
            if (orphan != null) {
                log.info("Deleting subscription {} left behind by the previous run", orphan);
                subscriptions.deleteSubscription(orphan);
            }
            account.markCleanedUp(now());
            renewAndSave(account);
            return true;
        } catch (ConcurrentAccountModificationException e) {
            log.info("Another replica took over the job for subscription '{}' during cleanup",
                    account.getSubscriptionName());
            return false;
        } catch (RuntimeException e) {
            log.error("Cleanup failed for subscription '{}'", account.getSubscriptionName(), e);
            recordOutcome(account, () -> account.failCleanup(describe(e), now()));
            return false;
        }
    }

    /** Steps 1 to 3, always a full run — nothing is skipped on the basis of an earlier attempt. */
    private void provision(Account account) {
        try {
            account.startProvisioning(now());
            renewAndSave(account);
            String azureSubscriptionId =
                    subscriptions.createSubscription(account.provisioningAlias(), account.getSubscriptionName());
            account.recordSubscriptionCreated(azureSubscriptionId, now());
            renewAndSave(account);
            log.info("Created subscription {}", azureSubscriptionId);

            account.startAssigningManagementGroup(now());
            renewAndSave(account);
            managementGroups.assignSubscription(azureSubscriptionId, account.getTargetManagementGroup());
            log.info("Assigned subscription {} to management group {}",
                    azureSubscriptionId, account.getTargetManagementGroup());

            account.startApplyingLabels(now());
            renewAndSave(account);
            subscriptions.applyTags(azureSubscriptionId, account.getLabels());

            account.complete(now());
            accounts.save(account);
            log.info("Provisioning completed for subscription '{}'", account.getSubscriptionName());
        } catch (ConcurrentAccountModificationException e) {
            // Losing the lease is the correct outcome, not an error: another replica owns this job
            // now, and it will run every step itself.
            log.info("Another replica took over the job for subscription '{}'; abandoning this run",
                    account.getSubscriptionName());
        } catch (RuntimeException e) {
            log.error("Provisioning failed for subscription '{}' during {}",
                    account.getSubscriptionName(), account.getStatus(), e);
            recordOutcome(account, () -> account.failStep(describe(e), now()));
        }
    }

    /**
     * What the previous attempt left behind, if anything.
     *
     * <p>Usually the recorded id. When that is missing the attempt may still have created a
     * subscription and died before the id reached Mongo, so the alias it would have used is looked
     * up — otherwise that subscription is orphaned in Azure with nothing pointing at it, and the
     * rerun quietly creates a second one alongside it.
     */
    private String orphanOf(Account account) {
        if (account.hasAzureSubscription()) {
            return account.getAzureSubscriptionId();
        }
        String alias = account.provisioningAlias();
        String recovered = subscriptions.findSubscriptionIdByAlias(alias).orElse(null);
        if (recovered != null) {
            log.warn("Recovered untracked subscription {} from alias {}; the attempt that created "
                    + "it died before recording the id", recovered, alias);
        }
        return recovered;
    }

    /**
     * Writes a terminal outcome, tolerating the case where the account has since been taken over —
     * an exception here would escape onto the worker thread and tell nobody anything useful.
     */
    private void recordOutcome(Account account, Runnable transition) {
        try {
            transition.run();
            accounts.save(account);
        } catch (RuntimeException e) {
            log.error("Could not record the outcome of the job for subscription '{}'",
                    account.getSubscriptionName(), e);
        }
    }

    private void renewAndSave(Account account) {
        account.renewLease(now().plus(properties.leaseDuration()), now());
        accounts.save(account);
    }

    private Instant now() {
        return clock.instant();
    }

    private static String describe(RuntimeException e) {
        return e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
    }
}
