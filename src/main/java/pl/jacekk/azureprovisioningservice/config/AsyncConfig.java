package pl.jacekk.azureprovisioningservice.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.time.Clock;
import java.util.concurrent.Executor;

/** The pool the provisioning workflow runs on. */
@Configuration
@EnableAsync
@EnableScheduling
public class AsyncConfig {

    /** Injected so lease expiry and the sweeper are deterministic under test. */
    @Bean
    public Clock systemClock() {
        return Clock.systemUTC();
    }

    @Bean("provisioningExecutor")
    public Executor provisioningExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(4);
        executor.setMaxPoolSize(16);
        executor.setQueueCapacity(100);
        executor.setThreadNamePrefix("provisioning-");
        executor.initialize();
        return executor;
    }
}
