package com.trailheadgear.orderintegration.spec.support;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.nio.file.Path;

import org.apache.kafka.clients.admin.Admin;
import org.apache.kafka.clients.admin.AdminClientConfig;
import org.apache.kafka.clients.admin.ListOffsetsResult;
import org.apache.kafka.clients.admin.OffsetSpec;
import org.apache.kafka.clients.admin.TopicDescription;
import org.apache.kafka.clients.consumer.OffsetAndMetadata;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.serialization.StringSerializer;
import org.citrusframework.actions.SendMessageAction;
import org.citrusframework.kafka.endpoint.KafkaEndpoint;
import org.citrusframework.kafka.endpoint.KafkaEndpointBuilder;

/**
 * Peakmart, the publisher of Order Events (design 004 §3.3, A-19, plan P-9, Task 10): a Citrus
 * {@code kafka-asynchronous} producer endpoint on topic {@value #TOPIC} of the run's Kafka container.
 *
 * <p><b>Value.</b> {@link #publish(Path)} sends the example file unchanged, with the String serializer (UTF-8). The
 * file must decode as UTF-8 and hold no Citrus expression ({@code ${…}}, {@code citrus:}): Citrus' Kafka message
 * converter runs dynamic-content replacement over a String payload, and with neither present it changes nothing.
 *
 * <p><b>No key (A-19).</b> Citrus' {@code KafkaMessageConverter} takes the record key only from the message header
 * {@code citrus_kafka_messageKey}; the send sets no such header, so the record key is null. Headers starting with
 * {@code citrus_} are not mapped to Kafka record headers, and no other header is set, so the record has no headers
 * either. The partition is the endpoint default 0 (the topic has one partition).
 *
 * <p><b>Offsets.</b> {@link #endOffsets()} sums the end offsets of every partition of the topic through the Kafka
 * {@code Admin} client; a snapshot before and after tells how many records were added ("the integration published
 * nothing").
 */
public final class Peakmart implements AutoCloseable {

    public static final String TOPIC = "peakmart.orders.v2";
    /** The integration's consumer group ({@code peakmart.group-id} in {@code src/main/resources/application.properties}). */
    public static final String INTEGRATION_GROUP = "trailhead-order-integration";

    private static final String ARRIVAL_TIMEOUT_KEY = "spec-test.arrival-timeout-ms";

    private final String bootstrapServers;
    private final long timeoutMs;
    private final KafkaEndpoint endpoint;

    public Peakmart() {
        SpecEnvironment env = SpecEnvironment.get();
        if (!TOPIC.equals(env.kafkaTopic())) {
            throw new IllegalStateException("The environment created topic " + env.kafkaTopic() + ", not " + TOPIC);
        }
        this.bootstrapServers = env.kafkaBootstrapServers();
        this.timeoutMs = env.millis(ARRIVAL_TIMEOUT_KEY);
        this.endpoint = new KafkaEndpointBuilder()
            .server(bootstrapServers)
            .topic(TOPIC)
            .keySerializer(StringSerializer.class)
            .valueSerializer(StringSerializer.class)
            .timeout(timeoutMs)
            .build();
        endpoint.setName("peakmart");
    }

    /** A {@code send} of the file's content, unchanged, without a key. */
    public SendMessageAction.Builder publish(Path exampleFile) {
        if (exampleFile == null) {
            throw new IllegalArgumentException("exampleFile is required");
        }
        String value = Partner.readExample(exampleFile.toAbsolutePath().normalize());
        SendMessageAction.Builder send = SendMessageAction.Builder.send(endpoint);
        send.message().body(value);
        return send;
    }

    /** The sum of the end offsets of all partitions of {@value #TOPIC}. */
    public long endOffsets() {
        try (Admin admin = Admin.create(adminConfig())) {
            TopicDescription description = admin.describeTopics(List.of(TOPIC)).allTopicNames()
                .get(timeoutMs, TimeUnit.MILLISECONDS).get(TOPIC);
            Map<TopicPartition, OffsetSpec> request = new java.util.HashMap<>();
            description.partitions().forEach(p -> request.put(new TopicPartition(TOPIC, p.partition()),
                OffsetSpec.latest()));
            Map<TopicPartition, ListOffsetsResult.ListOffsetsResultInfo> offsets =
                admin.listOffsets(request).all().get(timeoutMs, TimeUnit.MILLISECONDS);
            return offsets.values().stream().mapToLong(ListOffsetsResult.ListOffsetsResultInfo::offset).sum();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while reading the end offsets of " + TOPIC, e);
        } catch (ExecutionException | TimeoutException e) {
            throw new IllegalStateException("Cannot read the end offsets of " + TOPIC, e);
        }
    }

    /**
     * The sum of the offsets that consumer group {@code groupId} has committed on the partitions of {@value #TOPIC}
     * (0 for a partition without a commit). A committed offset is the next offset to read, so the sum equals
     * {@link #endOffsets()} once the group has handled every record. The integration commits each record manually as
     * the last step of its intake route, after the record was handled ({@code commit-kafka-offset}).
     */
    public long committedOffsets(String groupId) {
        if (groupId == null || groupId.isBlank()) {
            throw new IllegalArgumentException("groupId is required");
        }
        try (Admin admin = Admin.create(adminConfig())) {
            Map<TopicPartition, OffsetAndMetadata> committed = admin.listConsumerGroupOffsets(groupId)
                .partitionsToOffsetAndMetadata().get(timeoutMs, TimeUnit.MILLISECONDS);
            return committed.entrySet().stream()
                .filter(e -> TOPIC.equals(e.getKey().topic()) && e.getValue() != null)
                .mapToLong(e -> e.getValue().offset())
                .sum();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while reading the committed offsets of " + groupId, e);
        } catch (ExecutionException | TimeoutException e) {
            throw new IllegalStateException("Cannot read the committed offsets of " + groupId + " on " + TOPIC, e);
        }
    }

    private Map<String, Object> adminConfig() {
        int timeout = (int) Math.min(timeoutMs, Integer.MAX_VALUE);
        return Map.of(
            AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers,
            AdminClientConfig.REQUEST_TIMEOUT_MS_CONFIG, timeout,
            AdminClientConfig.DEFAULT_API_TIMEOUT_MS_CONFIG, timeout);
    }

    public KafkaEndpoint endpoint() {
        return endpoint;
    }

    /** Closes the Kafka producer, if one was created. */
    @Override
    public void close() {
        endpoint.destroy();
    }
}
