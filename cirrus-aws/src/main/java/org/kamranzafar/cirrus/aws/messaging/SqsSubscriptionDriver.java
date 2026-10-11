package org.kamranzafar.cirrus.aws.messaging;

import org.kamranzafar.cirrus.aws.AwsErrors;
import org.kamranzafar.cirrus.aws.AwsProvider;
import org.kamranzafar.cirrus.capability.Capabilities;
import org.kamranzafar.cirrus.error.CloudException;
import org.kamranzafar.cirrus.json.Json;
import org.kamranzafar.cirrus.messaging.MessagingCapability;
import org.kamranzafar.cirrus.messaging.spi.DriverMessage;
import org.kamranzafar.cirrus.messaging.spi.MessagingErrors;
import org.kamranzafar.cirrus.messaging.spi.SubscriptionDriver;
import software.amazon.awssdk.services.sqs.SqsClient;
import software.amazon.awssdk.services.sqs.model.DeleteMessageBatchRequestEntry;
import software.amazon.awssdk.services.sqs.model.Message;
import software.amazon.awssdk.services.sqs.model.MessageSystemAttributeName;
import software.amazon.awssdk.services.sqs.model.QueueAttributeName;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Receives from an SQS queue with long polling. The lease is the queue's visibility timeout, extended and
 * shortened with ChangeMessageVisibility. Messages an SNS topic delivered without raw message delivery
 * arrive in SNS' JSON envelope, which is unwrapped so the body and attributes match what was published.
 */
public final class SqsSubscriptionDriver implements SubscriptionDriver {

    /** SQS caps visibility at 12 hours. */
    private static final Duration MAX_VISIBILITY = Duration.ofHours(12);

    private final SqsClient sqs;
    private final String queueUrl;
    private final boolean fifo;
    private final Duration lease;

    public SqsSubscriptionDriver(SqsClient sqs, String queue) {
        this.sqs = sqs;
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
    public String provider() {
        return AwsProvider.ID;
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
        var response = sqs.receiveMessage(b -> b.queueUrl(queueUrl)
                .maxNumberOfMessages(Math.min(max, 10))
                .waitTimeSeconds((int) Math.min(20, Math.max(0, wait.toSeconds())))
                .messageAttributeNames("All")
                .messageSystemAttributeNames(MessageSystemAttributeName.APPROXIMATE_RECEIVE_COUNT,
                        MessageSystemAttributeName.SENT_TIMESTAMP, MessageSystemAttributeName.MESSAGE_GROUP_ID));
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
        long seconds = Math.min(MAX_VISIBILITY.toSeconds(), Math.max(0, timeout.toSeconds()));
        sqs.changeMessageVisibility(b -> b.queueUrl(queueUrl).receiptHandle(message.receipt())
                .visibilityTimeout((int) seconds));
    }

    @Override
    public Duration defaultLease() {
        return lease;
    }

    @Override
    public CloudException translate(Throwable error) {
        return MessagingErrors.translate(error, AwsProvider.ID, AwsErrors::translate);
    }

    @Override
    public <T> T unwrap(Class<T> nativeType) {
        return MessagingErrors.unwrap(nativeType, AwsProvider.ID, List.of(sqs));
    }
}
