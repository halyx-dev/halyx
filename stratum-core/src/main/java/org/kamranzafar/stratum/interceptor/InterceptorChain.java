package org.kamranzafar.stratum.interceptor;

import java.util.List;

/**
 * Runs interceptors in registration order (the first registered is outermost), ending with the
 * terminal provider call.
 */
public final class InterceptorChain {

    private static final InterceptorChain EMPTY = new InterceptorChain(List.of());

    private final List<Interceptor> interceptors;

    public InterceptorChain(List<Interceptor> interceptors) {
        this.interceptors = List.copyOf(interceptors);
    }

    public static InterceptorChain empty() {
        return EMPTY;
    }

    public <T> T execute(OperationContext context, Invocation<T> terminal) {
        return proceed(0, context, terminal);
    }

    private <T> T proceed(int index, OperationContext context, Invocation<T> terminal) {
        if (index == interceptors.size()) {
            return terminal.proceed();
        }
        return interceptors.get(index).intercept(context, () -> proceed(index + 1, context, terminal));
    }
}
