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
package dev.halyx.spring.messaging;

import dev.halyx.events.CloudEvent;
import dev.halyx.events.CloudEvents;
import dev.halyx.messaging.ConsumerOptions;
import dev.halyx.messaging.MessageConsumer;
import dev.halyx.messaging.MessageListener;
import dev.halyx.messaging.ReceivedMessage;
import dev.halyx.messaging.Subscription;
import dev.halyx.spring.stream.RecordListenerDecorator;
import dev.halyx.stream.ListenerOptions;
import dev.halyx.stream.ReceivedRecord;
import dev.halyx.stream.RecordListener;
import dev.halyx.stream.StreamConsumer;
import dev.halyx.stream.StreamListener;
import dev.halyx.stream.StreamProducer;
import dev.halyx.spring.autoconfigure.HalyxProperties;
import org.springframework.aop.support.AopUtils;
import org.springframework.beans.BeansException;
import org.springframework.beans.factory.BeanFactory;
import org.springframework.beans.factory.BeanFactoryAware;
import org.springframework.beans.factory.NoSuchBeanDefinitionException;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.context.SmartLifecycle;
import org.springframework.core.MethodIntrospector;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.util.ReflectionUtils;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Finds {@link HalyxListener} methods and runs a consumer for each while the application runs. Consumers
 * start in the last lifecycle phase, after everything they might call is ready, and stop first.
 */
public class HalyxListenerRegistrar implements BeanPostProcessor, SmartLifecycle, BeanFactoryAware {

    private record Endpoint(String key, String subscription, Object bean, Method method, int concurrency) {
    }

    private final ObjectProvider<HalyxProperties> properties;
    private final ObjectProvider<MessageListenerDecorator> decorators;
    private final ObjectProvider<RecordListenerDecorator> recordDecorators;
    private final List<Endpoint> endpoints = Collections.synchronizedList(new ArrayList<>());
    private final Map<String, MessageConsumer> consumers = Collections.synchronizedMap(new LinkedHashMap<>());
    private final Map<String, StreamListener> streamListeners = Collections.synchronizedMap(new LinkedHashMap<>());
    private BeanFactory beanFactory;
    private volatile boolean running;

    /** Resolved lazily at start, so this post-processor doesn't create those beans early. */
    public HalyxListenerRegistrar(ObjectProvider<HalyxProperties> properties,
                                   ObjectProvider<MessageListenerDecorator> decorators,
                                   ObjectProvider<RecordListenerDecorator> recordDecorators) {
        this.properties = Objects.requireNonNull(properties, "properties");
        this.decorators = Objects.requireNonNull(decorators, "decorators");
        this.recordDecorators = Objects.requireNonNull(recordDecorators, "recordDecorators");
    }

    @Override
    public void setBeanFactory(BeanFactory beanFactory) {
        this.beanFactory = beanFactory;
    }

    @Override
    public Object postProcessAfterInitialization(Object bean, String beanName) throws BeansException {
        Class<?> type = AopUtils.getTargetClass(bean);
        Map<Method, HalyxListener> methods = MethodIntrospector.selectMethods(type,
                (MethodIntrospector.MetadataLookup<HalyxListener>) m ->
                        AnnotatedElementUtils.findMergedAnnotation(m, HalyxListener.class));
        methods.forEach((method, annotation) -> {
            if (method.getParameterCount() != 1 || !supported(method.getParameterTypes()[0])) {
                throw new IllegalStateException("@HalyxListener method " + type.getName() + "." + method.getName()
                        + " must take one parameter: ReceivedMessage, CloudEvent or ReceivedRecord, String or byte[]");
            }
            Method invocable = AopUtils.selectInvocableMethod(method, bean.getClass());
            ReflectionUtils.makeAccessible(invocable);
            String key = annotation.value() + "#" + beanName + "." + method.getName();
            endpoints.add(new Endpoint(key, annotation.value(), bean, invocable, annotation.concurrency()));
        });
        return bean;
    }

    private static boolean supported(Class<?> parameter) {
        return parameter == ReceivedMessage.class || parameter == CloudEvent.class || parameter == ReceivedRecord.class
                || parameter == String.class || parameter == byte[].class;
    }

    @Override
    public void start() {
        synchronized (endpoints) {
            for (Endpoint endpoint : endpoints) {
                if (isStream(endpoint)) {
                    startStream(endpoint);
                    continue;
                }
                if (endpoint.method().getParameterTypes()[0] == ReceivedRecord.class) {
                    throw new IllegalStateException("@HalyxListener(\"" + endpoint.subscription() + "\") on "
                            + endpoint.method() + " takes a ReceivedRecord but refers to no stream consumer; configure "
                            + "halyx.stream-consumers." + endpoint.subscription());
                }
                Subscription subscription;
                try {
                    subscription = beanFactory.getBean(endpoint.subscription(), Subscription.class);
                } catch (NoSuchBeanDefinitionException e) {
                    throw new IllegalStateException("@HalyxListener(\"" + endpoint.subscription() + "\") on "
                            + endpoint.method() + " refers to no subscription or stream consumer; configure halyx.subscriptions."
                            + endpoint.subscription() + " or halyx.stream-consumers." + endpoint.subscription(), e);
                }
                HalyxProperties bound = properties.getIfAvailable();
                var config = bound == null ? null : bound.subscriptions().get(endpoint.subscription());
                ConsumerOptions options = config == null
                        ? (endpoint.concurrency() > 0 ? ConsumerOptions.DEFAULTS.withConcurrency(endpoint.concurrency())
                        : ConsumerOptions.DEFAULTS)
                        : config.listener().toConsumerOptions(endpoint.concurrency());
                MessageListener listener = message -> invoke(endpoint, message);
                for (MessageListenerDecorator decorator : decorators.orderedStream().toList()) {
                    listener = decorator.decorate(endpoint.subscription(), listener);
                }
                consumers.put(endpoint.key(), subscription.listen(listener, options));
            }
        }
        running = true;
    }

    private void startStream(Endpoint endpoint) {
        String name = endpoint.subscription();
        Class<?> parameter = endpoint.method().getParameterTypes()[0];
        if (parameter == ReceivedMessage.class || parameter == CloudEvent.class) {
            throw new IllegalStateException("@HalyxListener(\"" + name + "\") on " + endpoint.method()
                    + " refers to a stream consumer, whose methods take a ReceivedRecord, String or byte[]");
        }
        StreamConsumer consumer = beanFactory.getBean(name, StreamConsumer.class);
        HalyxProperties bound = properties.getIfAvailable();
        var config = bound == null ? null : bound.streamConsumers().get(name);
        ListenerOptions options = ListenerOptions.DEFAULTS;
        if (config != null) {
            String deadLetter = config.listener().deadLetter();
            StreamProducer deadLetterProducer = null;
            if (deadLetter != null) {
                if (!beanFactory.containsBean(deadLetter) || !beanFactory.isTypeMatch(deadLetter, StreamProducer.class)) {
                    throw new IllegalStateException("halyx.stream-consumers." + name + ".listener.dead-letter must name "
                            + "one of halyx.stream-producers, not '" + deadLetter + "'");
                }
                deadLetterProducer = beanFactory.getBean(deadLetter, StreamProducer.class);
            }
            options = config.listener().toListenerOptions(deadLetterProducer);
        }
        RecordListener listener = record -> invoke(endpoint, record);
        for (RecordListenerDecorator decorator : recordDecorators.orderedStream().toList()) {
            listener = decorator.decorate(name, listener);
        }
        streamListeners.put(endpoint.key(), consumer.listen(listener, options));
    }

    private static void invoke(Endpoint endpoint, ReceivedMessage message) throws Exception {
        Class<?> parameter = endpoint.method().getParameterTypes()[0];
        // A message that holds no CloudEvent fails like a throwing listener: retried, then dead-lettered.
        invoke(endpoint, parameter == String.class ? message.bodyAsString()
                : parameter == byte[].class ? message.body()
                : parameter == CloudEvent.class ? CloudEvents.decode(message) : message);
    }

    private static void invoke(Endpoint endpoint, ReceivedRecord record) throws Exception {
        Class<?> parameter = endpoint.method().getParameterTypes()[0];
        invoke(endpoint, parameter == String.class ? record.valueAsString()
                : parameter == byte[].class ? record.value() : record);
    }

    private static void invoke(Endpoint endpoint, Object argument) throws Exception {
        try {
            endpoint.method().invoke(endpoint.bean(), argument);
        } catch (InvocationTargetException e) {
            if (e.getCause() instanceof Exception cause) {
                throw cause;
            }
            throw e;
        }
    }

    @Override
    public void stop() {
        running = false;
        List<AutoCloseable> toClose = new ArrayList<>();
        synchronized (consumers) {
            toClose.addAll(consumers.values());
            consumers.clear();
        }
        synchronized (streamListeners) {
            toClose.addAll(streamListeners.values());
            streamListeners.clear();
        }
        // Close in parallel: each waits for its own running handlers.
        List<Thread> closing = new ArrayList<>();
        for (AutoCloseable consumer : toClose) {
            closing.add(Thread.ofVirtual().start(() -> {
                try {
                    consumer.close();
                } catch (Exception e) {
                    // closing anyway
                }
            }));
        }
        for (Thread thread : closing) {
            try {
                thread.join();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
        }
    }

    @Override
    public boolean isRunning() {
        return running;
    }

    /** Last to start, first to stop. */
    @Override
    public int getPhase() {
        return Integer.MAX_VALUE - 1000;
    }

    /** Running consumers by {@code <subscription>#<bean>.<method>}. */
    public Map<String, MessageConsumer> consumers() {
        synchronized (consumers) {
            return Map.copyOf(consumers);
        }
    }

    /** Running stream listeners by {@code <consumer>#<bean>.<method>}. */
    public Map<String, StreamListener> streamListeners() {
        synchronized (streamListeners) {
            return Map.copyOf(streamListeners);
        }
    }

    /** The subscription listeners, by {@code <subscription>#<bean>.<method>}, known once every bean has been created. */
    public List<String> subscriptionKeys() {
        synchronized (endpoints) {
            return endpoints.stream().filter(e -> !isStream(e)).map(Endpoint::key).toList();
        }
    }

    /** The stream listeners, by {@code <consumer>#<bean>.<method>}, known once every bean has been created. */
    public List<String> streamKeys() {
        synchronized (endpoints) {
            return endpoints.stream().filter(this::isStream).map(Endpoint::key).toList();
        }
    }

    private boolean isStream(Endpoint endpoint) {
        return beanFactory.containsBean(endpoint.subscription())
                && beanFactory.isTypeMatch(endpoint.subscription(), StreamConsumer.class);
    }
}
