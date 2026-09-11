package pl.jacekk.azureprovisioningservice.config;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import pl.jacekk.azureprovisioningservice.application.service.StaleJobSweeper;
import pl.jacekk.azureprovisioningservice.domain.port.out.AccountRepositoryPort;

import java.time.Clock;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/**
 * The sweeper's interval is a property resolved by Spring's scheduling post-processor, which fails
 * the whole context if it cannot parse the value. That failure would only show up at startup, so
 * it is worth pinning here.
 */
class SchedulingConfigurationTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withUserConfiguration(AsyncConfig.class)
            .withBean(AccountRepositoryPort.class, () -> mock(AccountRepositoryPort.class))
            .withBean(Clock.class, Clock::systemUTC)
            .withBean(ProvisioningProperties.class,
                    () -> new ProvisioningProperties(null, null, 0, "replica-a"))
            .withBean(StaleJobSweeper.class);

    @Test
    void schedulesTheSweeperOnTheDefaultInterval() {
        contextRunner.run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context).hasSingleBean(StaleJobSweeper.class);
        });
    }

    @Test
    void acceptsAConfiguredSweepInterval() {
        contextRunner.withPropertyValues("provisioning.sweep-interval=PT30S")
                .run(context -> assertThat(context).hasNotFailed());
    }

    @Test
    void providesTheAsyncExecutorTheWorkflowRunsOn() {
        contextRunner.run(context -> assertThat(context).hasBean("provisioningExecutor"));
    }
}
