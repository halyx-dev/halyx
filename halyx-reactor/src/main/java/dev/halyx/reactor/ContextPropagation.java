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
package dev.halyx.reactor;

import io.micrometer.context.ContextSnapshot;
import io.micrometer.context.ContextSnapshotFactory;
import reactor.util.context.ContextView;

import java.util.concurrent.Callable;

/**
 * Carries context such as MDC, the current Micrometer observation and security context to the thread that
 * runs a Halyx call, when Micrometer's context-propagation library is on the classpath. Values come from the
 * subscribing thread's thread locals and, overriding them, from the subscriber's Reactor context, through the
 * accessors registered with {@code ContextRegistry}. Without the library, calls run as they are.
 */
abstract class ContextPropagation {

    static ContextPropagation detect() {
        try {
            Class.forName("io.micrometer.context.ContextSnapshotFactory", false, ContextPropagation.class.getClassLoader());
            return new Micrometer();
        } catch (ClassNotFoundException | LinkageError e) {
            return new None();
        }
    }

    abstract Object capture(ContextView subscriberContext);

    abstract <T> T call(Object snapshot, Callable<T> call) throws Exception;

    private static final class None extends ContextPropagation {
        @Override
        Object capture(ContextView subscriberContext) {
            return null;
        }

        @Override
        <T> T call(Object snapshot, Callable<T> call) throws Exception {
            return call.call();
        }
    }

    private static final class Micrometer extends ContextPropagation {
        private final ContextSnapshotFactory factory = ContextSnapshotFactory.builder().build();

        @Override
        Object capture(ContextView subscriberContext) {
            return factory.captureAll(subscriberContext);
        }

        @Override
        <T> T call(Object snapshot, Callable<T> call) throws Exception {
            ContextSnapshot.Scope scope = ((ContextSnapshot) snapshot).setThreadLocals();
            try {
                return call.call();
            } finally {
                scope.close();
            }
        }
    }
}
