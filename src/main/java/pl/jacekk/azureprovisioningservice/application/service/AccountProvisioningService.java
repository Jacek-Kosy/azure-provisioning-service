package pl.jacekk.azureprovisioningservice.application.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.stereotype.Service;
import pl.jacekk.azureprovisioningservice.config.ProvisioningProperties;
import pl.jacekk.azureprovisioningservice.domain.model.Account;
import pl.jacekk.azureprovisioningservice.domain.model.AccountNotFoundException;
import pl.jacekk.azureprovisioningservice.domain.model.InvalidLabelsException;
import pl.jacekk.azureprovisioningservice.domain.model.JobLease;
import pl.jacekk.azureprovisioningservice.domain.model.Labels;
import pl.jacekk.azureprovisioningservice.domain.port.in.AcceptanceOutcome;
import pl.jacekk.azureprovisioningservice.domain.port.in.AccountAcceptance;
import pl.jacekk.azureprovisioningservice.domain.port.in.CreateAccountCommand;
import pl.jacekk.azureprovisioningservice.domain.port.in.CreateAccountUseCase;
import pl.jacekk.azureprovisioningservice.domain.port.in.GetAccountStatusUseCase;
import pl.jacekk.azureprovisioningservice.domain.port.out.AccountRepositoryPort;
import pl.jacekk.azureprovisioningservice.domain.port.out.DuplicateSubscriptionNameException;
import pl.jacekk.azureprovisioningservice.domain.port.out.LabelValidationPort;
import pl.jacekk.azureprovisioningservice.domain.port.out.LabelValidationResult;

import java.time.Clock;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/**
 * Decides what happens to an incoming request, then hands the work to the async workflow.
 *
 * <p>The subscription name is the idempotency key. Rather than reading the existing account and
 * deciding from what it sees — which two replicas can do simultaneously and both act on — the
 * service attempts the write first and lets the database arbitrate: a unique index for the fresh
 * insert, a conditional update for the retry claim.
 */
@Service
public class AccountProvisioningService implements CreateAccountUseCase, GetAccountStatusUseCase {

    private static final Logger log = LoggerFactory.getLogger(AccountProvisioningService.class);

    private final AccountRepositoryPort accounts;
    private final LabelValidationPort labelValidation;
    private final ProvisioningWorkflow workflow;
    private final Clock clock;
    private final ProvisioningProperties properties;

    public AccountProvisioningService(AccountRepositoryPort accounts,
                                      LabelValidationPort labelValidation,
                                      ProvisioningWorkflow workflow,
                                      Clock clock,
                                      ProvisioningProperties properties) {
        this.accounts = accounts;
        this.labelValidation = labelValidation;
        this.workflow = workflow;
        this.clock = clock;
        this.properties = properties;
    }

    @Override
    public AccountAcceptance createAccount(CreateAccountCommand command) {
        LabelValidationResult validation = labelValidation.validate(command.labels());
        if (!validation.valid()) {
            throw new InvalidLabelsException(validation.violations());
        }

        Instant now = clock.instant();
        Account account = Account.newRequest(
                UUID.randomUUID().toString(),
                command.subscriptionName(),
                command.targetManagementGroup(),
                Labels.of(command.labels()),
                newLease(now),
                now);
        try {
            Account inserted = accounts.insertNew(account);
            withJobContext(inserted, () -> log.info("Accepted a new provisioning job for subscription '{}'",
                    inserted.getSubscriptionName()));
            workflow.run(inserted.getId());
            return new AccountAcceptance(inserted.getId(), AcceptanceOutcome.CREATED);
        } catch (DuplicateSubscriptionNameException e) {
            return acceptRetryOrReportConflict(command.subscriptionName(), now);
        }
    }

    /**
     * The name is taken. Claim it only if the job behind it failed; the claim is atomic, so if
     * another replica is accepting the same retry right now, exactly one of us wins and the other
     * reports a conflict.
     */
    private AccountAcceptance acceptRetryOrReportConflict(String subscriptionName, Instant now) {
        Optional<Account> claimed = accounts.claimForRetry(subscriptionName, newLease(now), now);
        if (claimed.isPresent()) {
            Account account = claimed.get();
            withJobContext(account, () -> log.info(
                    "Accepted a retry for subscription '{}'; every step will reconcile",
                    account.getSubscriptionName()));
            workflow.run(account.getId());
            return new AccountAcceptance(account.getId(), AcceptanceOutcome.RETRY_ACCEPTED);
        }

        Account existing = accounts.findBySubscriptionName(subscriptionName)
                .orElseThrow(() -> new IllegalStateException(
                        "Subscription name '%s' was taken and then vanished; retry the request"
                                .formatted(subscriptionName)));
        log.info("Rejected a duplicate request for subscription '{}'; job {} is {}",
                subscriptionName, existing.getId(), existing.getStatus());
        return new AccountAcceptance(existing.getId(), AcceptanceOutcome.ALREADY_IN_PROGRESS);
    }

    @Override
    public Account getAccount(String accountId) {
        return accounts.findById(accountId).orElseThrow(() -> new AccountNotFoundException(accountId));
    }

    private JobLease newLease(Instant now) {
        return new JobLease(
                UUID.randomUUID().toString(),
                properties.ownerId(),
                now.plus(properties.leaseDuration()));
    }

    private void withJobContext(Account account, Runnable logging) {
        MDC.put(AsyncProvisioningWorkflow.MDC_JOB_ID, account.getJobId());
        MDC.put(AsyncProvisioningWorkflow.MDC_ACCOUNT_ID, account.getId());
        try {
            logging.run();
        } finally {
            MDC.remove(AsyncProvisioningWorkflow.MDC_JOB_ID);
            MDC.remove(AsyncProvisioningWorkflow.MDC_ACCOUNT_ID);
        }
    }
}
