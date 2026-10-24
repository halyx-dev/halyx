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
package dev.halyx.spring.data.document.repository;

import dev.halyx.nosql.DocumentStore;
import dev.halyx.reactor.ReactiveHalyx;
import org.springframework.core.KotlinDetector;
import org.springframework.core.MethodParameter;
import org.springframework.core.ResolvableType;
import org.springframework.data.domain.Window;
import org.springframework.data.repository.query.QueryMethod;
import org.springframework.data.repository.query.RepositoryQuery;
import reactor.core.publisher.Flux;

import java.util.stream.Stream;

/**
 * A derived query of a reactive repository: the blocking {@link PartTreeDocumentQuery}, with the same supported
 * shapes and failures, run on {@code ReactiveHalyx}'s scheduler. A {@code Flux} reads page by page as the
 * subscriber asks; a {@code Mono} holds one entity, an {@code existsBy...} answer, or a {@code Window}. In Kotlin,
 * a {@code Flow} reads like a {@code Flux}, and a {@code suspend fun} returns what the {@code Mono} would hold.
 */
final class ReactivePartTreeDocumentQuery<T extends Record> implements RepositoryQuery {

    private final PartTreeDocumentQuery<T> query;
    private final ReactiveHalyx reactive;

    ReactivePartTreeDocumentQuery(QueryMethod method, DocumentStore store, EntityModel<T> model, ReactiveHalyx reactive) {
        this.query = new PartTreeDocumentQuery<>(method, store, model, kind(method));
        this.reactive = reactive;
    }

    private static PartTreeDocumentQuery.Kind kind(QueryMethod method) {
        java.lang.reflect.Method javaMethod = methodOf(method);
        // For a suspend fun, whose Java return type is Object, MethodParameter resolves the type Kotlin declares.
        ResolvableType returned = ResolvableType.forMethodParameter(new MethodParameter(javaMethod, -1));
        Class<?> type = returned.resolve(Object.class);
        if (Flux.class.isAssignableFrom(type) || org.reactivestreams.Publisher.class == type
                || type.getName().equals("kotlinx.coroutines.flow.Flow")) {
            return PartTreeDocumentQuery.Kind.STREAM;
        }
        Class<?> held = KotlinDetector.isSuspendingFunction(javaMethod) ? type : returned.getGeneric(0).resolve(Object.class);
        if (held == Window.class) {
            return PartTreeDocumentQuery.Kind.WINDOW;
        }
        return PartTreeDocumentQuery.Kind.SINGLE;
    }

    private static java.lang.reflect.Method methodOf(QueryMethod method) {
        // QueryMethod keeps the method itself private; the lookup strategy hands it in through the subclass below.
        return ((ReactiveQueryMethod) method).method();
    }

    @Override
    public QueryMethod getQueryMethod() {
        return query.getQueryMethod();
    }

    @Override
    @SuppressWarnings("unchecked")
    public Object execute(Object[] parameters) {
        return switch (query.kind()) {
            case STREAM, LIST -> reactive.stream(() -> (Stream<T>) query.execute(parameters));
            case SINGLE, EXISTS, WINDOW -> reactive.call(() -> query.execute(parameters));
        };
    }

    /** A {@link QueryMethod} that remembers its method, so the reactive return type can choose the query's kind. */
    static final class ReactiveQueryMethod extends QueryMethod {
        private final java.lang.reflect.Method method;

        ReactiveQueryMethod(java.lang.reflect.Method method, org.springframework.data.repository.core.RepositoryMetadata metadata,
                            org.springframework.data.projection.ProjectionFactory factory) {
            super(method, metadata, factory);
            this.method = method;
        }

        java.lang.reflect.Method method() {
            return method;
        }
    }
}
