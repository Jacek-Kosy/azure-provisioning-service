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
 * Runs the three provisioning steps. Every state change goes through the aggregate, so an
 * out-of-order step is rejected by the domain rather than written to Mongo.
 *
 * <p>There is one path, not two. Because each step reconciles — adopting what is already there
 * instead of duplicating it — a retry is simply this run again, and nothing has to be torn down
 * first.
 */
@Component
public class AsyncProvisioningWorkflow implements ProvisioningWorkflow {

    private static final Logger log = LoggerFactory.getLogger(AsyncProvisioningWorkflow.class);

    private static final String MDC_JOB_ID = "jobId";
    private static final String MDC_ACCOUNT_ID = "accountId";

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
    public void run(String accountId) {
        Optional<Account> found = accounts.findById(accountId);
        if (found.isEmpty()) {
            log.warn("Provisioning run skipped, account {} no longer exists", accountId);
            return;
        }
        Account account = found.get();
        MDC.put(MDC_JOB_ID, account.getJobId());
        MDC.put(MDC_ACCOUNT_ID, account.getId());
        try {
            log.info("Provisioning run started for subscription '{}'", account.getSubscriptionName());
            provision(account);
        } finally {
            MDC.remove(MDC_JOB_ID);
            MDC.remove(MDC_ACCOUNT_ID);
        }
    }

    private void provision(Account account) {
        try {
            account.startProvisioning(now());
            renewAndSave(account);
            String azureSubscriptionId = subscriptions.ensureSubscription(
                    account.provisioningAlias(), account.getSubscriptionName());
            account.recordSubscription(azureSubscriptionId, now());
            renewAndSave(account);

            account.startAssigningManagementGroup(now());
            renewAndSave(account);
            managementGroups.ensurePlacedUnder(azureSubscriptionId, account.getTargetManagementGroup());

            account.startApplyingLabels(now());
            renewAndSave(account);
            subscriptions.ensureTags(azureSubscriptionId, account.getLabels());

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
            recordFailure(account, e);
        }
    }

    /** A failure here has since been taken over, and an exception would only reach a dead thread. */
    private void recordFailure(Account account, RuntimeException cause) {
        try {
            account.failStep(describe(cause), now());
            accounts.save(account);
        } catch (RuntimeException e) {
            log.error("Could not record the failure of the job for subscription '{}'",
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
