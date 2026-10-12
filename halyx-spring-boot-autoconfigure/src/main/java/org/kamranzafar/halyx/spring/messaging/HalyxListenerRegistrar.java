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
package org.kamranzafar.halyx.spring.messaging;

import org.kamranzafar.halyx.messaging.ConsumerOptions;
import org.kamranzafar.halyx.messaging.MessageConsumer;
import org.kamranzafar.halyx.messaging.MessageListener;
import org.kamranzafar.halyx.messaging.ReceivedMessage;
import org.kamranzafar.halyx.messaging.Subscription;
import org.kamranzafar.halyx.spring.autoconfigure.HalyxProperties;
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
    private final List<Endpoint> endpoints = Collections.synchronizedList(new ArrayList<>());
    private final Map<String, MessageConsumer> consumers = Collections.synchronizedMap(new LinkedHashMap<>());
    private BeanFactory beanFactory;
    private volatile boolean running;

    /** Resolved lazily at start, so this post-processor doesn't create those beans early. */
    public HalyxListenerRegistrar(ObjectProvider<HalyxProperties> properties,
                                   ObjectProvider<MessageListenerDecorator> decorators) {
        this.properties = Objects.requireNonNull(properties, "properties");
        this.decorators = Objects.requireNonNull(decorators, "decorators");
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
                        + " must take one parameter: ReceivedMessage, String or byte[]");
            }
            Method invocable = AopUtils.selectInvocableMethod(method, bean.getClass());
            ReflectionUtils.makeAccessible(invocable);
            String key = annotation.value() + "#" + beanName + "." + method.getName();
            endpoints.add(new Endpoint(key, annotation.value(), bean, invocable, annotation.concurrency()));
        });
        return bean;
    }

    private static boolean supported(Class<?> parameter) {
        return parameter == ReceivedMessage.class || parameter == String.class || parameter == byte[].class;
    }

    @Override
    public void start() {
        synchronized (endpoints) {
            for (Endpoint endpoint : endpoints) {
                Subscription subscription;
                try {
                    subscription = beanFactory.getBean(endpoint.subscription(), Subscription.class);
                } catch (NoSuchBeanDefinitionException e) {
                    throw new IllegalStateException("@HalyxListener(\"" + endpoint.subscription() + "\") on "
                            + endpoint.method() + " refers to no subscription; configure halyx.subscriptions."
                            + endpoint.subscription(), e);
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

    private static void invoke(Endpoint endpoint, ReceivedMessage message) throws Exception {
        Class<?> parameter = endpoint.method().getParameterTypes()[0];
        Object argument = parameter == String.class ? message.bodyAsString()
                : parameter == byte[].class ? message.body() : message;
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
        List<MessageConsumer> toClose;
        synchronized (consumers) {
            toClose = new ArrayList<>(consumers.values());
            consumers.clear();
        }
        // Close in parallel: each waits for its own running handlers.
        List<Thread> closing = new ArrayList<>();
        for (MessageConsumer consumer : toClose) {
            closing.add(Thread.ofVirtual().start(consumer::close));
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

    /** The subscriptions that have listeners, known once every bean has been created. */
    public List<String> subscriptionKeys() {
        synchronized (endpoints) {
            return endpoints.stream().map(Endpoint::key).toList();
        }
    }
}
