package pl.jacekk.azureprovisioningservice.config;

import org.slf4j.MDC;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.task.TaskDecorator;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.Map;
import java.util.concurrent.Executor;

/**
 * The pool the provisioning workflow runs on, plus the piece that makes the correlation id
 * survive the hop off the request thread: without the decorator, the {@code jobId} put into the
 * MDC while accepting the POST would be missing from every log line the workflow writes.
 */
@Configuration
@EnableAsync
@EnableScheduling
public class AsyncConfig {

    @Bean("provisioningExecutor")
    public Executor provisioningExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(4);
        executor.setMaxPoolSize(16);
        executor.setQueueCapacity(100);
        executor.setThreadNamePrefix("provisioning-");
        executor.setTaskDecorator(mdcPropagating());
        executor.initialize();
        return executor;
    }

    private static TaskDecorator mdcPropagating() {
        return runnable -> {
            Map<String, String> submitterContext = MDC.getCopyOfContextMap();
            return () -> {
                Map<String, String> workerContext = MDC.getCopyOfContextMap();
                if (submitterContext != null) {
                    MDC.setContextMap(submitterContext);
                }
                try {
                    runnable.run();
                } finally {
                    if (workerContext == null) {
                        MDC.clear();
                    } else {
                        MDC.setContextMap(workerContext);
                    }
                }
            };
        };
    }
}
