package com.gpay.payment.config;

import org.slf4j.MDC;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.task.TaskDecorator;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.annotation.EnableScheduling;

import java.util.Map;

@Configuration
@EnableAsync        // AuditClient.log runs off the request thread
@EnableScheduling   // PendingTransactionScheduler: expire stale top-ups, reconcile unknown-outcome transfers
public class AsyncConfig {

    /**
     * Boot applies a single TaskDecorator bean to its auto-configured executor.
     * MDC is thread-local, so without this @Async work loses traceId/userId (logs and audit rows).
     */
    @Bean
    public TaskDecorator mdcTaskDecorator() {
        return task -> {
            Map<String, String> context = MDC.getCopyOfContextMap();
            return () -> {
                if (context != null) MDC.setContextMap(context);
                try {
                    task.run();
                } finally {
                    MDC.clear();
                }
            };
        };
    }
}
