/*
 * Copyright 2026 Kamran Zafar
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package dev.halyx.aws.messaging;

import dev.halyx.aws.AwsErrors;
import dev.halyx.aws.AwsProvider;
import dev.halyx.capability.Capabilities;
import dev.halyx.error.CloudException;
import dev.halyx.json.Json;
import dev.halyx.messaging.MessagingCapability;
import dev.halyx.messaging.spi.AbstractSubscriptionDriver;
import dev.halyx.messaging.spi.DriverMessage;
import software.amazon.awssdk.services.sqs.SqsAsyncClient;
import software.amazon.awssdk.services.sqs.SqsClient;
import software.amazon.awssdk.services.sqs.model.ChangeMessageVisibilityRequest;
import software.amazon.awssdk.services.sqs.model.DeleteMessageBatchRequestEntry;
import software.amazon.awssdk.services.sqs.model.Message;
import software.amazon.awssdk.services.sqs.model.MessageSystemAttributeName;
import software.amazon.awssdk.services.sqs.model.QueueAttributeName;
import software.amazon.awssdk.services.sqs.model.ReceiveMessageRequest;
import software.amazon.awssdk.services.sqs.model.ReceiveMessageResponse;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.function.Supplier;

/**
 * Receives from an SQS queue with long polling. The lease is the queue's visibility timeout, extended and
 * shortened with ChangeMessageVisibility. Messages an SNS topic delivered without raw message delivery
 * arrive in SNS' JSON envelope, which is unwrapped so the body and attributes match what was published.
 */
public final class SqsSubscriptionDriver extends AbstractSubscriptionDriver {

    /** SQS caps visibility at 12 hours. */
    private static final Duration MAX_VISIBILITY = Duration.ofHours(12);

    private final SqsClient sqs;
    private final Supplier<SqsAsyncClient> sqsAsync;
    private final String queueUrl;
    private final boolean fifo;
    private final Duration lease;

    /** @param sqsAsync the non-blocking client for asynchronous receives, made when first asked for */
    public SqsSubscriptionDriver(SqsClient sqs, String queue, Supplier<SqsAsyncClient> sqsAsync) {
        super(AwsProvider.ID);
        this.sqs = sqs;
        this.sqsAsync = sqsAsync;
        this.queueUrl = resolve(sqs, queue);
        this.fifo = queueUrl.endsWith(".fifo");
        String visibility;
        try {
            visibility = sqs.getQueueAttributes(b -> b.queueUrl(queueUrl).attributeNames(QueueAttributeName.VISIBILITY_TIMEOUT))
                    .attributes().get(QueueAttributeName.VISIBILITY_TIMEOUT);
        } catch (RuntimeException e) {
            throw AwsErrors.translate(e).orElseThrow(() -> e);
        }
        this.lease = Duration.ofSeconds(visibility == null ? 30 : Long.parseLong(visibility));
    }

    /** A queue URL as is, or the URL of the queue with that name. */
    static String resolve(SqsClient sqs, String queue) {
        if (queue.startsWith("https://") || queue.startsWith("http://")) {
            return queue;
        }
        try {
            return sqs.getQueueUrl(b -> b.queueName(queue)).queueUrl();
        } catch (RuntimeException e) {
            throw AwsErrors.translate(e).orElseThrow(() -> e);
        }
    }

    @Override
    public String name() {
        return queueUrl;
    }

    @Override
    public Capabilities capabilities() {
        return fifo ? Capabilities.of(MessagingCapability.ORDERING, MessagingCapability.NACK_DELAY)
                : Capabilities.of(MessagingCapability.NACK_DELAY);
    }

    @Override
    public List<DriverMessage> receive(int max, Duration wait) {
        return toDriverMessages(sqs.receiveMessage(receiveRequest(max, wait)));
    }

    /** The long poll without a waiting thread. */
    @Override
    public CompletableFuture<List<DriverMessage>> receiveAsync(int max, Duration wait, Executor blocking) {
        return sqsAsync.get().receiveMessage(receiveRequest(max, wait)).thenApply(this::toDriverMessages);
    }

    @Override
    public CompletableFuture<Void> ackAsync(DriverMessage message, Executor blocking) {
        return sqsAsync.get().deleteMessage(b -> b.queueUrl(queueUrl).receiptHandle(message.receipt())).thenApply(r -> null);
    }

    @Override
    public CompletableFuture<Void> nackAsync(DriverMessage message, Duration delay, Executor blocking) {
        return sqsAsync.get().changeMessageVisibility(visibilityRequest(message, delay)).thenApply(r -> null);
    }

    @Override
    public CompletableFuture<Void> extendLeaseAsync(DriverMessage message, Duration lease, Executor blocking) {
        return sqsAsync.get().changeMessageVisibility(visibilityRequest(message, lease)).thenApply(r -> null);
    }

    private ReceiveMessageRequest receiveRequest(int max, Duration wait) {
        return ReceiveMessageRequest.builder().queueUrl(queueUrl)
                .maxNumberOfMessages(Math.min(max, 10))
                .waitTimeSeconds((int) Math.min(20, Math.max(0, wait.toSeconds())))
                .messageAttributeNames("All")
                .messageSystemAttributeNames(MessageSystemAttributeName.APPROXIMATE_RECEIVE_COUNT,
                        MessageSystemAttributeName.SENT_TIMESTAMP, MessageSystemAttributeName.MESSAGE_GROUP_ID)
                .build();
    }

    private List<DriverMessage> toDriverMessages(ReceiveMessageResponse response) {
        List<DriverMessage> messages = new ArrayList<>(response.messages().size());
        for (Message message : response.messages()) {
            messages.add(toDriverMessage(message));
        }
        return messages;
    }


    private static DriverMessage toDriverMessage(Message message) {
        Map<String, String> attributes = new LinkedHashMap<>();
        message.messageAttributes().forEach((k, v) -> {
            if (v.stringValue() != null) {
                attributes.put(k, v.stringValue());
            }
        });
        String id = message.messageId();
        String text = message.body();
        Instant published = Optional.ofNullable(message.attributes().get(MessageSystemAttributeName.SENT_TIMESTAMP))
                .map(ms -> Instant.ofEpochMilli(Long.parseLong(ms))).orElse(null);
        Optional<Map<String, Object>> envelope = attributes.isEmpty() ? snsEnvelope(text) : Optional.empty();
        if (envelope.isPresent()) {
            var json = envelope.get();
            id = String.valueOf(json.get("MessageId"));
            text = String.valueOf(json.get("Message"));
            if (json.get("MessageAttributes") instanceof Map<?, ?> attrs) {
                attrs.forEach((k, v) -> {
                    if (v instanceof Map<?, ?> value && value.get("Value") != null) {
                        attributes.put(String.valueOf(k), String.valueOf(value.get("Value")));
                    }
                });
            }
            if (json.get("Timestamp") instanceof String ts) {
                published = Instant.parse(ts);
            }
        }
        int attempt = Optional.ofNullable(message.attributes().get(MessageSystemAttributeName.APPROXIMATE_RECEIVE_COUNT))
                .map(Integer::parseInt).orElse(0);
        String group = message.attributes().get(MessageSystemAttributeName.MESSAGE_GROUP_ID);
        return new DriverMessage(id, message.receiptHandle(), AwsBodies.decode(text, attributes),
                AwsBodies.withoutEncoding(attributes), group, published, attempt);
    }

    /** SNS' notification envelope, when the subscription doesn't use raw message delivery. */
    private static Optional<Map<String, Object>> snsEnvelope(String body) {
        if (body == null || !body.startsWith("{") || !body.contains("\"TopicArn\"")) {
            return Optional.empty();
        }
        return Json.parseObject(body).filter(json -> "Notification".equals(json.get("Type"))
                && json.containsKey("Message") && json.containsKey("MessageId"));
    }

    @Override
    public int maxReceiveBatch() {
        return 10;
    }

    @Override
    public void ack(DriverMessage message) {
        sqs.deleteMessage(b -> b.queueUrl(queueUrl).receiptHandle(message.receipt()));
    }

    /** DeleteMessageBatch: up to 10 per request. Entries SQS rejects are logged and redelivered later. */
    @Override
    public void ackAll(List<DriverMessage> messages) {
        List<DeleteMessageBatchRequestEntry> entries = new ArrayList<>(messages.size());
        for (int i = 0; i < messages.size(); i++) {
            entries.add(DeleteMessageBatchRequestEntry.builder().id(String.valueOf(i))
                    .receiptHandle(messages.get(i).receipt()).build());
        }
        var response = sqs.deleteMessageBatch(b -> b.queueUrl(queueUrl).entries(entries));
        if (!response.failed().isEmpty()) {
            var failure = response.failed().getFirst();
            throw AwsErrors.fromCode(failure.code(), "SQS rejected " + response.failed().size()
                    + " of " + messages.size() + " acks: " + failure.message());
        }
    }

    @Override
    public int maxAckBatch() {
        return 10;
    }

    @Override
    public void nack(DriverMessage message, Duration delay) {
        changeVisibility(message, delay);
    }

    @Override
    public void extendLease(DriverMessage message, Duration lease) {
        changeVisibility(message, lease);
    }

    private void changeVisibility(DriverMessage message, Duration timeout) {
        sqs.changeMessageVisibility(visibilityRequest(message, timeout));
    }

    private ChangeMessageVisibilityRequest visibilityRequest(DriverMessage message, Duration timeout) {
        long seconds = Math.min(MAX_VISIBILITY.toSeconds(), Math.max(0, timeout.toSeconds()));
        return ChangeMessageVisibilityRequest.builder().queueUrl(queueUrl).receiptHandle(message.receipt())
                .visibilityTimeout((int) seconds).build();
    }

    @Override
    public Duration defaultLease() {
        return lease;
    }

    @Override
    protected Optional<CloudException> translateNative(Throwable error) {
        return AwsErrors.translate(error);
    }

    @Override
    protected List<Object> nativeClients() {
        return List.of(sqs);
    }
}
