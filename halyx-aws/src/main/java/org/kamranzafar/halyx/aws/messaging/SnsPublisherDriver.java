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
import org.kamranzafar.halyx.error.NotFoundException;
import org.kamranzafar.halyx.messaging.MessagingCapability;
import org.kamranzafar.halyx.messaging.OutgoingMessage;
import org.kamranzafar.halyx.messaging.spi.MessagingErrors;
import org.kamranzafar.halyx.messaging.spi.PublisherDriver;
import software.amazon.awssdk.services.sns.SnsClient;
import software.amazon.awssdk.services.sns.model.MessageAttributeValue;
import software.amazon.awssdk.services.sns.model.PublishBatchRequestEntry;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Publishes to an SNS topic, which fans out to its subscribed SQS queues. FIFO topics ({@code .fifo})
 * support ordering keys (message groups) and deduplication ids; standard topics support neither, and SNS
 * has no per-message delay. At most 10 message attributes, including Halyx' own.
 */
public final class SnsPublisherDriver implements PublisherDriver {

    private final SnsClient sns;
    private final String topicArn;
    private final boolean fifo;

    public SnsPublisherDriver(SnsClient sns, String topic) {
        this.sns = sns;
        this.topicArn = topic.startsWith("arn:") ? topic : resolve(sns, topic);
        this.fifo = topicArn.endsWith(".fifo");
    }

    /** Finds the topic's ARN by name, so configuration can use plain names. */
    private static String resolve(SnsClient sns, String name) {
        try {
            for (var page : sns.listTopicsPaginator()) {
                for (var topic : page.topics()) {
                    if (topic.topicArn().endsWith(":" + name)) {
                        return topic.topicArn();
                    }
                }
            }
        } catch (RuntimeException e) {
            throw AwsErrors.translate(e).orElseThrow(() -> e);
        }
        throw new NotFoundException("No SNS topic named " + name, null, AwsProvider.ID, "NotFound");
    }

    @Override
    public String provider() {
        return AwsProvider.ID;
    }

    @Override
    public String topic() {
        return topicArn;
    }

    @Override
    public Capabilities capabilities() {
        return fifo ? Capabilities.of(MessagingCapability.ORDERING, MessagingCapability.DEDUPLICATION) : Capabilities.none();
    }

    @Override
    public List<String> publish(List<OutgoingMessage> messages) {
        List<PublishBatchRequestEntry> entries = new ArrayList<>(messages.size());
        for (int i = 0; i < messages.size(); i++) {
            OutgoingMessage message = messages.get(i);
            AwsBodies.Encoded body = AwsBodies.encode(message.bodyUnsafe());
            var entry = PublishBatchRequestEntry.builder().id(String.valueOf(i)).message(body.text())
                    .messageAttributes(attributes(message.attributes(), body.base64()));
            message.orderingKey().ifPresent(entry::messageGroupId);
            message.deduplicationId().ifPresent(entry::messageDeduplicationId);
            entries.add(entry.build());
        }
        var response = sns.publishBatch(b -> b.topicArn(topicArn).publishBatchRequestEntries(entries));
        if (!response.failed().isEmpty()) {
            var failure = response.failed().getFirst();
            throw AwsErrors.fromCode(failure.code(), "SNS publish to " + topicArn + " failed for "
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
        return MessagingErrors.unwrap(nativeType, AwsProvider.ID, List.of(sns));
    }
}
