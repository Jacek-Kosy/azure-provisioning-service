package pl.jacekk.azureprovisioningservice.application.service;

import org.junit.jupiter.api.Test;
import pl.jacekk.azureprovisioningservice.config.ProvisioningProperties;
import pl.jacekk.azureprovisioningservice.domain.model.Account;
import pl.jacekk.azureprovisioningservice.domain.model.JobLease;
import pl.jacekk.azureprovisioningservice.domain.model.Labels;
import pl.jacekk.azureprovisioningservice.domain.model.ProvisioningStatus;
import pl.jacekk.azureprovisioningservice.domain.port.out.AccountRepositoryPort;
import pl.jacekk.azureprovisioningservice.domain.port.out.ConcurrentAccountModificationException;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class StaleJobSweeperTest {

    private static final Instant NOW = Instant.parse("2026-09-11T10:00:00Z");
    private static final Labels LABELS = Labels.of(Map.of(
            Labels.COST_CENTER_ID, "CC-1001",
            Labels.COST_CENTER_ID_PROVIDER, "sap",
            Labels.PROJECT_INTERNAL_ID, "PRJ-42",
            Labels.PROJECT_INTERNAL_ID_PROVIDER, "servicenow"));

    private final AccountRepositoryPort accounts = mock(AccountRepositoryPort.class);
    private final ProvisioningProperties properties =
            new ProvisioningProperties(Duration.ofMinutes(5), Duration.ofMinutes(1), 50, "replica-b");
    private final StaleJobSweeper sweeper =
            new StaleJobSweeper(accounts, Clock.fixed(NOW, ZoneOffset.UTC), properties);

    /** A job whose owning replica died halfway through creating the subscription. */
    private static Account abandonedBy(String id, String owner) {
        Account account = Account.newRequest(id, "name-" + id, "mg-workloads", LABELS,
                new JobLease("job-" + id, owner, NOW.minusSeconds(60)), NOW.minusSeconds(600));
        account.startProvisioning(NOW.minusSeconds(500));
        return account;
    }

    @Test
    void marksAnExpiredJobFailedSoTheOrdinaryRetryPathCanRecoverIt() {
        Account stale = abandonedBy("acc-1", "replica-dead");
        when(accounts.findStale(NOW, 50)).thenReturn(List.of(stale));

        sweeper.sweep();

        verify(accounts).save(stale);
        assertThat(stale.getStatus()).isEqualTo(ProvisioningStatus.FAILED);
        assertThat(stale.getErrorDetail())
                .isEqualTo("ABANDONED: lease held by replica-dead expired while status was CREATING_SUBSCRIPTION");
    }

    @Test
    void asksForNoMoreThanTheConfiguredBatch() {
        when(accounts.findStale(any(), org.mockito.ArgumentMatchers.anyInt())).thenReturn(List.of());

        sweeper.sweep();

        verify(accounts).findStale(eq(NOW), eq(50));
    }

    @Test
    void writesNothingWhenNoJobIsStale() {
        when(accounts.findStale(any(), org.mockito.ArgumentMatchers.anyInt())).thenReturn(List.of());

        sweeper.sweep();

        verify(accounts, never()).save(any());
    }

    @Test
    void leavesAJobAloneWhenAnotherReplicaRecoveredItFirst() {
        Account contested = abandonedBy("acc-1", "replica-dead");
        Account next = abandonedBy("acc-2", "replica-dead");
        when(accounts.findStale(any(), org.mockito.ArgumentMatchers.anyInt()))
                .thenReturn(List.of(contested, next));
        when(accounts.save(contested)).thenThrow(new ConcurrentAccountModificationException("acc-1"));

        sweeper.sweep();

        verify(accounts).save(next);
        assertThat(next.getStatus()).isEqualTo(ProvisioningStatus.FAILED);
    }

    @Test
    void keepsSweepingAfterAnUnexpectedFailureOnOneAccount() {
        Account broken = abandonedBy("acc-1", "replica-dead");
        Account next = abandonedBy("acc-2", "replica-dead");
        when(accounts.findStale(any(), org.mockito.ArgumentMatchers.anyInt()))
                .thenReturn(List.of(broken, next));
        when(accounts.save(broken)).thenThrow(new RuntimeException("mongo unreachable"));

        sweeper.sweep();

        verify(accounts).save(next);
    }
}
