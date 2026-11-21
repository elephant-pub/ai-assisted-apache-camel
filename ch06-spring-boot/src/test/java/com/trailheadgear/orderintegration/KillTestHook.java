package com.trailheadgear.orderintegration;

import org.apache.camel.CamelContext;
import org.apache.camel.NamedNode;
import org.apache.camel.Processor;
import org.apache.camel.spi.InterceptStrategy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.core.env.Environment;

/**
 * Kill-test hook (design 002 D5, B-1): holds the Kafka offset commit back so that a kill -9 can land after the
 * receipt insert and before the commit. It replaces the prototype's {@code delay-before-commit} route step, so the
 * production routes carry no test hook.
 *
 * <p>Active only with the profile {@code kill-test}. It wraps only the processor with node id
 * {@code commit-kafka-offset} and sleeps {@code kill-test.commit-delay-ms} (default 0) on the calling thread before
 * it, so the commit stays on the Kafka poll thread, as with the prototype's synchronous delay.
 */
@Configuration
@Profile("kill-test")
public class KillTestHook {

    static final String COMMIT_NODE_ID = "commit-kafka-offset";

    private static final Logger LOG = LoggerFactory.getLogger(KillTestHook.class);

    @Bean
    public InterceptStrategy killTestInterceptStrategy(Environment environment) {
        long commitDelayMs = environment.getProperty("kill-test.commit-delay-ms", Long.class, 0L);
        LOG.info("Kill-test hook active commitDelayMs={}", commitDelayMs);
        return new CommitDelayStrategy(commitDelayMs);
    }

    static final class CommitDelayStrategy implements InterceptStrategy {

        private final long commitDelayMs;

        CommitDelayStrategy(long commitDelayMs) {
            this.commitDelayMs = commitDelayMs;
        }

        @Override
        public Processor wrapProcessorInInterceptors(CamelContext context, NamedNode definition, Processor target,
                Processor nextTarget) {
            if (definition == null || !COMMIT_NODE_ID.equals(definition.getId())) {
                return target;
            }
            return exchange -> {
                if (commitDelayMs > 0) {
                    try {
                        Thread.sleep(commitDelayMs);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        throw e;
                    }
                }
                target.process(exchange);
            };
        }
    }
}
