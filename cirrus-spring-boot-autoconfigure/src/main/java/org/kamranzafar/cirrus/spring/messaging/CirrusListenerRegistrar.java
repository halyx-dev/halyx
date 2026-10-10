package org.kamranzafar.cirrus.spring.messaging;

import org.kamranzafar.cirrus.messaging.ConsumerOptions;
import org.kamranzafar.cirrus.messaging.MessageConsumer;
import org.kamranzafar.cirrus.messaging.MessageListener;
import org.kamranzafar.cirrus.messaging.ReceivedMessage;
import org.kamranzafar.cirrus.messaging.Subscription;
import org.kamranzafar.cirrus.spring.autoconfigure.CirrusProperties;
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
 * Finds {@link CirrusListener} methods and runs a consumer for each while the application runs. Consumers
 * start in the last lifecycle phase, after everything they might call is ready, and stop first.
 */
public class CirrusListenerRegistrar implements BeanPostProcessor, SmartLifecycle, BeanFactoryAware {

    private record Endpoint(String key, String subscription, Object bean, Method method, int concurrency) {
    }

    private final ObjectProvider<CirrusProperties> properties;
    private final ObjectProvider<MessageListenerDecorator> decorators;
    private final List<Endpoint> endpoints = Collections.synchronizedList(new ArrayList<>());
    private final Map<String, MessageConsumer> consumers = Collections.synchronizedMap(new LinkedHashMap<>());
    private BeanFactory beanFactory;
    private volatile boolean running;

    /** Resolved lazily at start, so this post-processor doesn't create those beans early. */
    public CirrusListenerRegistrar(ObjectProvider<CirrusProperties> properties,
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
        Map<Method, CirrusListener> methods = MethodIntrospector.selectMethods(type,
                (MethodIntrospector.MetadataLookup<CirrusListener>) m ->
                        AnnotatedElementUtils.findMergedAnnotation(m, CirrusListener.class));
        methods.forEach((method, annotation) -> {
            if (method.getParameterCount() != 1 || !supported(method.getParameterTypes()[0])) {
                throw new IllegalStateException("@CirrusListener method " + type.getName() + "." + method.getName()
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
                    throw new IllegalStateException("@CirrusListener(\"" + endpoint.subscription() + "\") on "
                            + endpoint.method() + " refers to no subscription; configure cirrus.subscriptions."
                            + endpoint.subscription(), e);
                }
                CirrusProperties bound = properties.getIfAvailable();
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
