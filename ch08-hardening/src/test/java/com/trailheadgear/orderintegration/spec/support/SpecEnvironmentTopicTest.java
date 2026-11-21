package com.trailheadgear.orderintegration.spec.support;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Proxy;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.apache.kafka.clients.admin.Admin;
import org.apache.kafka.clients.admin.CreateTopicsResult;
import org.apache.kafka.clients.admin.DescribeClusterResult;
import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.common.KafkaFuture;
import org.apache.kafka.common.Node;
import org.apache.kafka.common.errors.TimeoutException;
import org.apache.kafka.common.errors.TopicExistsException;
import org.apache.kafka.common.internals.KafkaFutureImpl;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/**
 * Design 005 §3.10 row 1 (Kafka topic creation flaky, 2 of 11 runs): {@code SpecEnvironment.createTopic} waits for
 * the broker ({@code describeCluster} until a node answers, within the startup timeout), then creates the topic with up
 * to 3 tries; {@code TopicExistsException} counts as created. A unit test with a stub {@link Admin} (a
 * {@link Proxy}): no Docker, no broker. The stub answers {@code describeCluster} and {@code createTopics} only, and
 * records whether it was closed (the caller owns it).
 */
@Timeout(value = 30, unit = TimeUnit.SECONDS)
class SpecEnvironmentTopicTest {

    private static final String TOPIC = "peakmart.orders.v2";
    private static final Duration STARTUP = Duration.ofSeconds(5);

    @Test
    void waitsForTheBrokerThenRetriesTheCreationUntilItSucceeds() throws Exception {
        StubAdmin stub = new StubAdmin(1, Arrays.asList(retriable(), retriable(), null));

        SpecEnvironment.createTopic(stub.admin(), TOPIC, STARTUP);

        assertThat(stub.describeCalls.get()).as("describeCluster calls (fails once, then a node answers)")
            .isEqualTo(2);
        assertThat(stub.createdTopics).as("createTopics tries (fails twice, then succeeds)")
            .containsExactly(TOPIC, TOPIC, TOPIC);
        assertThat(stub.closed).as("the caller's Admin is not closed").isFalse();
    }

    @Test
    void aTopicThatExistsCountsAsCreated() throws Exception {
        StubAdmin stub = new StubAdmin(0, List.of(new TopicExistsException("Topic '" + TOPIC + "' already exists.")));

        SpecEnvironment.createTopic(stub.admin(), TOPIC, STARTUP);

        assertThat(stub.createdTopics).as("createTopics tries").containsExactly(TOPIC);
    }

    @Test
    void threeFailedTriesFailNamingTheTopic() {
        StubAdmin stub = new StubAdmin(0, List.of(retriable(), retriable(), retriable(), retriable()));

        assertThatThrownBy(() -> SpecEnvironment.createTopic(stub.admin(), TOPIC, STARTUP))
            .as("the failure after three tries").hasMessageContaining(TOPIC);
        assertThat(stub.createdTopics).as("createTopics tries").containsExactly(TOPIC, TOPIC, TOPIC);
    }

    @Test
    void aBrokerThatNeverAnswersFailsWithinTheStartupTimeout() {
        StubAdmin stub = new StubAdmin(Integer.MAX_VALUE, List.of());
        long started = System.nanoTime();

        assertThatThrownBy(() -> SpecEnvironment.createTopic(stub.admin(), TOPIC, Duration.ofMillis(500)))
            .as("the failure when no node answers").hasMessageContaining(TOPIC);
        assertThat(Duration.ofNanos(System.nanoTime() - started)).as("time to give up")
            .isLessThan(Duration.ofSeconds(5));
        assertThat(stub.createdTopics).as("no creation without a broker").isEmpty();
    }

    @Test
    void theStubAnswersAsDescribed() throws Exception {
        StubAdmin stub = new StubAdmin(1, List.of(retriable()));
        Admin admin = stub.admin();

        assertThatThrownBy(() -> admin.describeCluster().nodes().get()).hasCauseInstanceOf(TimeoutException.class);
        assertThat(admin.describeCluster().nodes().get()).hasSize(1);
        List<NewTopic> topic = List.of(new NewTopic(TOPIC, 1, (short) 1));
        assertThatThrownBy(() -> admin.createTopics(topic).all().get()).hasCauseInstanceOf(TimeoutException.class);
        admin.createTopics(topic).all().get();
        assertThat(stub.createdTopics).containsExactly(TOPIC, TOPIC);
    }

    // ---------------------------------------------------------------------------------------------- the stub

    private static RuntimeException retriable() {
        return new TimeoutException("Timed out waiting for a node assignment (stub)");
    }

    /**
     * {@code describeCluster} fails {@code describeFailures} times, then answers one node; {@code createTopics} try
     * {@code n} completes with {@code createOutcomes.get(n)} (null: created; past the end: created).
     */
    private static final class StubAdmin {

        final AtomicInteger describeCalls = new AtomicInteger();
        final List<String> createdTopics = new ArrayList<>();
        volatile boolean closed;

        private final int describeFailures;
        private final List<RuntimeException> createOutcomes;

        StubAdmin(int describeFailures, List<RuntimeException> createOutcomes) {
            this.describeFailures = describeFailures;
            this.createOutcomes = createOutcomes;
        }

        Admin admin() {
            InvocationHandler handler = (proxy, method, args) -> switch (method.getName()) {
                case "describeCluster" -> describeCluster();
                case "createTopics" -> createTopics((Collection<?>) args[0]);
                case "close" -> {
                    closed = true;
                    yield null;
                }
                case "toString" -> "StubAdmin";
                case "hashCode" -> System.identityHashCode(proxy);
                case "equals" -> proxy == args[0];
                default -> throw new UnsupportedOperationException("stub Admin: " + method.getName());
            };
            return (Admin) Proxy.newProxyInstance(Admin.class.getClassLoader(), new Class<?>[] {Admin.class}, handler);
        }

        private synchronized DescribeClusterResult describeCluster() throws Exception {
            int call = describeCalls.incrementAndGet();
            if (call <= describeFailures) {
                return describeClusterResult(failed(retriable()));
            }
            return describeClusterResult(KafkaFuture.completedFuture(List.of(new Node(1, "127.0.0.1", 9092))));
        }

        private synchronized CreateTopicsResult createTopics(Collection<?> newTopics) {
            int attempt = createdTopics.size();
            Map<String, KafkaFuture<CreateTopicsResult.TopicMetadataAndConfig>> futures = new java.util.LinkedHashMap<>();
            for (Object o : newTopics) {
                String name = ((NewTopic) o).name();
                createdTopics.add(name);
                RuntimeException outcome = attempt < createOutcomes.size() ? createOutcomes.get(attempt) : null;
                futures.put(name, outcome == null ? KafkaFuture.completedFuture(null) : failed(outcome));
            }
            return new CreateTopicsResult(futures) {
            };
        }

        /** {@code DescribeClusterResult}'s constructor is package-private: nodes first, every other future answered. */
        private static DescribeClusterResult describeClusterResult(KafkaFuture<?> nodes) throws Exception {
            Constructor<?> constructor = DescribeClusterResult.class.getDeclaredConstructors()[0];
            constructor.setAccessible(true);
            Class<?>[] types = constructor.getParameterTypes();
            Object[] args = new Object[types.length];
            boolean nodesSet = false;
            for (int i = 0; i < types.length; i++) {
                if (KafkaFuture.class.isAssignableFrom(types[i])) {
                    if (!nodesSet) {
                        args[i] = nodes;
                        nodesSet = true;
                    } else {
                        args[i] = nodes.isCompletedExceptionally() ? nodes : KafkaFuture.completedFuture(null);
                    }
                }
            }
            return (DescribeClusterResult) constructor.newInstance(args);
        }

        private static <T> KafkaFuture<T> failed(RuntimeException e) {
            KafkaFutureImpl<T> future = new KafkaFutureImpl<>();
            future.completeExceptionally(e);
            return future;
        }
    }
}
