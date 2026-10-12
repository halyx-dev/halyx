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
package org.kamranzafar.halyx.aws.messaging;

import org.kamranzafar.halyx.aws.AwsErrors;
import org.kamranzafar.halyx.aws.AwsProvider;
import org.kamranzafar.halyx.capability.Capabilities;
import org.kamranzafar.halyx.error.CloudException;
import org.kamranzafar.halyx.error.InvalidArgumentException;
import org.kamranzafar.halyx.messaging.MessagingCapability;
import org.kamranzafar.halyx.messaging.OutgoingMessage;
import org.kamranzafar.halyx.messaging.spi.MessagingErrors;
import org.kamranzafar.halyx.messaging.spi.PublisherDriver;
import software.amazon.awssdk.services.sqs.SqsClient;
import software.amazon.awssdk.services.sqs.model.MessageAttributeValue;
import software.amazon.awssdk.services.sqs.model.SendMessageBatchRequestEntry;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Sends straight to an SQS queue (point to point, no topic). Standard queues support per-message delays up
 * to 15 minutes; FIFO queues support ordering keys and deduplication ids but only queue-level delays.
 */
public final class SqsPublisherDriver implements PublisherDriver {

    static final Duration MAX_DELAY = Duration.ofMinutes(15);

    private final SqsClient sqs;
    private final String queueUrl;
    private final boolean fifo;

    public SqsPublisherDriver(SqsClient sqs, String queue) {
        this.sqs = sqs;
        this.queueUrl = SqsSubscriptionDriver.resolve(sqs, queue);
        this.fifo = queueUrl.endsWith(".fifo");
    }

    @Override
    public String provider() {
        return AwsProvider.ID;
    }

    @Override
    public String topic() {
        return queueUrl;
    }

    @Override
    public Capabilities capabilities() {
        return fifo ? Capabilities.of(MessagingCapability.ORDERING, MessagingCapability.DEDUPLICATION)
                : Capabilities.of(MessagingCapability.DELAYED_DELIVERY);
    }

    @Override
    public List<String> publish(List<OutgoingMessage> messages) {
        List<SendMessageBatchRequestEntry> entries = new ArrayList<>(messages.size());
        for (int i = 0; i < messages.size(); i++) {
            OutgoingMessage message = messages.get(i);
            AwsBodies.Encoded body = AwsBodies.encode(message.bodyUnsafe());
            var entry = SendMessageBatchRequestEntry.builder().id(String.valueOf(i)).messageBody(body.text())
                    .messageAttributes(attributes(message.attributes(), body.base64()));
            message.orderingKey().ifPresent(entry::messageGroupId);
            message.deduplicationId().ifPresent(entry::messageDeduplicationId);
            if (message.delay().isPresent()) {
                Duration delay = message.delay().get();
                if (delay.compareTo(MAX_DELAY) > 0) {
                    throw new InvalidArgumentException("SQS delays are at most 15 minutes, not " + delay,
                            null, AwsProvider.ID, null);
                }
                entry.delaySeconds((int) Math.max(1, delay.toSeconds()));
            }
            entries.add(entry.build());
        }
        var response = sqs.sendMessageBatch(b -> b.queueUrl(queueUrl).entries(entries));
        if (!response.failed().isEmpty()) {
            var failure = response.failed().getFirst();
            throw AwsErrors.fromCode(failure.code(), "SQS send to " + queueUrl + " failed for "
                    + response.failed().size() + " message(s): " + failure.message());
        }
        String[] ids = new String[messages.size()];
        response.successful().forEach(s -> ids[Integer.parseInt(s.id())] = s.messageId());
        return List.of(ids);
    }

    static Map<String, MessageAttributeValue> attributes(Map<String, String> attributes, boolean base64) {
        Map<String, MessageAttributeValue> out = new LinkedHashMap<>();
        attributes.forEach((k, v) -> out.put(k, MessageAttributeValue.builder().dataType("String").stringValue(v).build()));
        if (base64) {
            out.put(AwsBodies.ENCODING, MessageAttributeValue.builder().dataType("String").stringValue(AwsBodies.BASE64).build());
        }
        return out;
    }

    @Override
    public int maxBatchSize() {
        return 10;
    }

    @Override
    public long maxMessageBytes() {
        return 256 * 1024;
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
