package pl.jacekk.azureprovisioningservice.adapter.out.persistence;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.mongodb.test.autoconfigure.DataMongoTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Import;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.index.IndexInfo;
import org.springframework.data.mongodb.core.query.Query;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.mongodb.MongoDBContainer;
import pl.jacekk.azureprovisioningservice.config.MongoIndexConfig;
import pl.jacekk.azureprovisioningservice.domain.model.Account;
import pl.jacekk.azureprovisioningservice.domain.model.JobLease;
import pl.jacekk.azureprovisioningservice.domain.model.Labels;
import pl.jacekk.azureprovisioningservice.domain.model.ProvisioningStatus;
import pl.jacekk.azureprovisioningservice.domain.port.out.ConcurrentAccountModificationException;
import pl.jacekk.azureprovisioningservice.domain.port.out.DuplicateSubscriptionNameException;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DataMongoTest
@Testcontainers(disabledWithoutDocker = true)
@Import({MongoAccountRepositoryAdapter.class, MongoIndexConfig.class})
class MongoAccountRepositoryAdapterIT {

    @Container
    @ServiceConnection
    static final MongoDBContainer MONGO = new MongoDBContainer("mongo:7.0");

    private static final Instant NOW = Instant.parse("2026-09-11T10:00:00Z");
    private static final Labels LABELS = Labels.of(Map.of(
            Labels.COST_CENTER_ID, "CC-1001",
            Labels.COST_CENTER_ID_PROVIDER, "sap",
            Labels.PROJECT_INTERNAL_ID, "PRJ-42",
            Labels.PROJECT_INTERNAL_ID_PROVIDER, "servicenow"));

    @Autowired
    private MongoAccountRepositoryAdapter accounts;

    @Autowired
    private MongoTemplate mongoTemplate;

    @BeforeEach
    void clearCollection() {
        mongoTemplate.remove(new Query(), AccountDocument.class);
    }

    private static Account pending(String id, String subscriptionName) {
        return Account.newRequest(id, subscriptionName, "mg-workloads", LABELS,
                new JobLease("job-" + id, "replica-a", NOW.plusSeconds(300)), NOW);
    }

    private static Account failedWithOrphan(String id, String subscriptionName) {
        Account account = pending(id, subscriptionName);
        account.startProvisioning(NOW);
        account.recordSubscription("sub-orphan", NOW);
        account.startAssigningManagementGroup(NOW);
        account.failStep("management group not found", NOW);
        return account;
    }

    @Test
    void roundTripsEveryFieldOfTheAggregate() {
        accounts.insertNew(failedWithOrphan("acc-1", "team-alpha-prod"));

        Account loaded = accounts.findById("acc-1").orElseThrow();

        assertThat(loaded.getId()).isEqualTo("acc-1");
        assertThat(loaded.getSubscriptionName()).isEqualTo("team-alpha-prod");
        assertThat(loaded.getTargetManagementGroup()).isEqualTo("mg-workloads");
        assertThat(loaded.getLabels()).isEqualTo(LABELS);
        assertThat(loaded.getStatus()).isEqualTo(ProvisioningStatus.FAILED);
        assertThat(loaded.getAzureSubscriptionId()).isEqualTo("sub-orphan");
        assertThat(loaded.getErrorDetail())
                .isEqualTo("Step ASSIGNING_MANAGEMENT_GROUP failed: management group not found");
        assertThat(loaded.getCreatedAt()).isEqualTo(NOW);
        assertThat(loaded.getJobId()).isEqualTo("job-acc-1");
        assertThat(loaded.getOwnerId()).isEqualTo("replica-a");
        assertThat(loaded.getLeaseExpiresAt()).isEqualTo(NOW.plusSeconds(300));
        assertThat(loaded.provisioningAlias())
                .as("a run on another replica must derive the same alias to adopt what exists")
                .isEqualTo("acct-acc-1");
    }

    @Test
    void findsAnAccountByItsSubscriptionName() {
        accounts.insertNew(pending("acc-1", "team-alpha-prod"));

        assertThat(accounts.findBySubscriptionName("team-alpha-prod")).get()
                .extracting(Account::getId).isEqualTo("acc-1");
        assertThat(accounts.findBySubscriptionName("nothing-here")).isEmpty();
    }

    @Test
    void declaresAUniqueIndexOnTheSubscriptionName() {
        List<IndexInfo> indexes = mongoTemplate.indexOps(AccountDocument.class).getIndexInfo();

        assertThat(indexes)
                .filteredOn(IndexInfo::isUnique)
                .anySatisfy(index -> assertThat(index.getIndexFields())
                        .singleElement()
                        .extracting(field -> field.getKey())
                        .isEqualTo("subscriptionName"));
    }

    @Test
    void refusesASecondAccountWithTheSameSubscriptionName() {
        accounts.insertNew(pending("acc-1", "team-alpha-prod"));

        assertThatThrownBy(() -> accounts.insertNew(pending("acc-2", "team-alpha-prod")))
                .isInstanceOf(DuplicateSubscriptionNameException.class)
                .hasMessageContaining("team-alpha-prod");
    }

    @Test
    void claimsAFailedAccountForRetryUnderTheNewLease() {
        accounts.insertNew(failedWithOrphan("acc-1", "team-alpha-prod"));
        JobLease lease = new JobLease("job-2", "replica-b", NOW.plusSeconds(600));

        Account claimed = accounts.claimForRetry("team-alpha-prod", lease, NOW).orElseThrow();

        assertThat(claimed.getStatus()).isEqualTo(ProvisioningStatus.PENDING);
        assertThat(claimed.getErrorDetail()).isNull();
        assertThat(claimed.getJobId()).isEqualTo("job-2");
        assertThat(claimed.getOwnerId()).isEqualTo("replica-b");
        assertThat(claimed.getLeaseExpiresAt()).isEqualTo(NOW.plusSeconds(600));
        assertThat(claimed.getAzureSubscriptionId())
                .as("kept, so the rerun adopts it")
                .isEqualTo("sub-orphan");
        assertThat(accounts.findById("acc-1").orElseThrow().getStatus())
                .isEqualTo(ProvisioningStatus.PENDING);
    }

    @Test
    void refusesToClaimAnAccountThatHasNotFailed() {
        accounts.insertNew(pending("acc-1", "team-alpha-prod"));

        Optional<Account> claimed = accounts.claimForRetry("team-alpha-prod",
                new JobLease("job-2", "replica-b", NOW.plusSeconds(600)), NOW);

        assertThat(claimed).isEmpty();
        assertThat(accounts.findById("acc-1").orElseThrow().getStatus())
                .isEqualTo(ProvisioningStatus.PENDING);
    }

    @Test
    void refusesToClaimAnUnknownSubscriptionName() {
        assertThat(accounts.claimForRetry("nothing-here",
                new JobLease("job-2", "replica-b", NOW.plusSeconds(600)), NOW)).isEmpty();
    }

    @Test
    void letsExactlyOneReplicaWinWhenManyClaimTheSameRetryAtOnce() throws Exception {
        accounts.insertNew(failedWithOrphan("acc-1", "team-alpha-prod"));
        int replicas = 8;
        ExecutorService pool = Executors.newFixedThreadPool(replicas);
        CountDownLatch startLine = new CountDownLatch(1);
        List<Callable<Boolean>> claims = new ArrayList<>();
        for (int replica = 0; replica < replicas; replica++) {
            String name = "replica-" + replica;
            claims.add(() -> {
                startLine.await();
                return accounts.claimForRetry("team-alpha-prod",
                        new JobLease("job-" + name, name, NOW.plusSeconds(600)), NOW).isPresent();
            });
        }

        List<Future<Boolean>> futures = new ArrayList<>();
        for (Callable<Boolean> claim : claims) {
            futures.add(pool.submit(claim));
        }
        startLine.countDown();

        long winners = 0;
        for (Future<Boolean> future : futures) {
            if (future.get()) {
                winners++;
            }
        }
        pool.shutdownNow();

        assertThat(winners).as("a retry must start on exactly one replica").isEqualTo(1);
    }

    @Test
    void persistsStateAdvancedInMemory() {
        Account account = accounts.insertNew(pending("acc-1", "team-alpha-prod"));
        account.startProvisioning(NOW.plusSeconds(1));
        account.recordSubscription("sub-123", NOW.plusSeconds(2));

        accounts.save(account);

        Account reloaded = accounts.findById("acc-1").orElseThrow();
        assertThat(reloaded.getStatus()).isEqualTo(ProvisioningStatus.CREATING_SUBSCRIPTION);
        assertThat(reloaded.getAzureSubscriptionId()).isEqualTo("sub-123");
        assertThat(reloaded.getUpdatedAt()).isEqualTo(NOW.plusSeconds(2));
    }

    @Test
    void refusesAWriteBuiltOnAStaleReadOfTheAccount() {
        accounts.insertNew(pending("acc-1", "team-alpha-prod"));
        Account onReplicaA = accounts.findById("acc-1").orElseThrow();
        Account onReplicaB = accounts.findById("acc-1").orElseThrow();

        onReplicaA.startProvisioning(NOW.plusSeconds(1));
        accounts.save(onReplicaA);

        onReplicaB.startProvisioning(NOW.plusSeconds(1));
        assertThatThrownBy(() -> accounts.save(onReplicaB))
                .isInstanceOf(ConcurrentAccountModificationException.class);
    }

    @Test
    void treatsAClaimAsAWriteThatInvalidatesEarlierReads() {
        accounts.insertNew(failedWithOrphan("acc-1", "team-alpha-prod"));
        Account staleRead = accounts.findById("acc-1").orElseThrow();

        accounts.claimForRetry("team-alpha-prod",
                new JobLease("job-2", "replica-b", NOW.plusSeconds(600)), NOW).orElseThrow();

        staleRead.startRetry(new JobLease("job-3", "replica-c", NOW.plusSeconds(600)), NOW);
        assertThatThrownBy(() -> accounts.save(staleRead))
                .isInstanceOf(ConcurrentAccountModificationException.class);
    }

    @Test
    void findsOnlyInFlightAccountsWhoseLeaseHasExpired() {
        Account expired = pending("acc-expired", "expired-name");
        expired.startProvisioning(NOW);
        expired.renewLease(NOW.minusSeconds(10), NOW);
        accounts.insertNew(expired);

        Account live = pending("acc-live", "live-name");
        live.startProvisioning(NOW);
        live.renewLease(NOW.plusSeconds(600), NOW);
        accounts.insertNew(live);

        Account finished = pending("acc-done", "done-name");
        finished.startProvisioning(NOW);
        finished.recordSubscription("sub-1", NOW);
        finished.startAssigningManagementGroup(NOW);
        finished.startApplyingLabels(NOW);
        finished.complete(NOW);
        finished.renewLease(NOW.minusSeconds(10), NOW);
        accounts.insertNew(finished);

        Account alreadyFailed = failedWithOrphan("acc-failed", "failed-name");
        alreadyFailed.renewLease(NOW.minusSeconds(10), NOW);
        accounts.insertNew(alreadyFailed);

        List<Account> stale = accounts.findStale(NOW, 100);

        assertThat(stale).extracting(Account::getId).containsExactly("acc-expired");
    }

    @Test
    void limitsHowManyStaleAccountsOneSweepRecovers() {
        for (int i = 0; i < 5; i++) {
            Account expired = pending("acc-" + i, "name-" + i);
            expired.startProvisioning(NOW);
            expired.renewLease(NOW.minusSeconds(10), NOW);
            accounts.insertNew(expired);
        }

        assertThat(accounts.findStale(NOW, 2)).hasSize(2);
    }
}
