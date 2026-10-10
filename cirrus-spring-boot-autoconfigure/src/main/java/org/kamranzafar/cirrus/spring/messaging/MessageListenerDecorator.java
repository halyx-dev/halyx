package org.kamranzafar.cirrus.spring.messaging;

import org.kamranzafar.cirrus.messaging.MessageListener;

/** Wraps every {@link CirrusListener} method's listener, for cross-cutting concerns such as tracing. */
@FunctionalInterface
public interface MessageListenerDecorator {

    MessageListener decorate(String subscription, MessageListener listener);
}
