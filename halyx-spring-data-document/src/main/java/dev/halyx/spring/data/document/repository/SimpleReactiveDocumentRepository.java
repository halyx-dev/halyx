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

import dev.halyx.reactor.ReactiveHalyx;
import dev.halyx.spring.data.document.ReactiveDocumentRepository;
import org.reactivestreams.Publisher;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * The base implementation of every {@link ReactiveDocumentRepository}: the blocking repository's CRUD, with its
 * optimistic writes, run on {@code ReactiveHalyx}'s scheduler.
 */
public class SimpleReactiveDocumentRepository<T extends Record, ID> implements ReactiveDocumentRepository<T, ID> {

    private final SimpleDocumentRepository<T, ID> blocking;
    private final ReactiveHalyx reactive;

    SimpleReactiveDocumentRepository(SimpleDocumentRepository<T, ID> blocking, ReactiveHalyx reactive) {
        this.blocking = blocking;
        this.reactive = reactive;
    }

    /** The blocking repository this runs, for custom repository fragments. */
    public SimpleDocumentRepository<T, ID> blocking() {
        return blocking;
    }

    @Override
    public <S extends T> Mono<S> save(S entity) {
        return reactive.call(() -> blocking.save(entity));
    }

    @Override
    public <S extends T> Flux<S> saveAll(Iterable<S> entities) {
        return reactive.call(() -> blocking.saveAll(entities)).flatMapIterable(saved -> saved);
    }

    @Override
    public <S extends T> Flux<S> saveAll(Publisher<S> entities) {
        return Flux.from(entities).concatMap(this::save);
    }

    @Override
    public Mono<T> findById(ID id) {
        return reactive.call(() -> blocking.findById(id).orElse(null));
    }

    @Override
    public Mono<Boolean> existsById(ID id) {
        return reactive.call(() -> blocking.existsById(id));
    }

    @Override
    public Flux<T> findAllById(Iterable<ID> ids) {
        return reactive.call(() -> blocking.findAllById(ids)).flatMapIterable(found -> found);
    }

    @Override
    public Mono<Void> deleteById(ID id) {
        return reactive.call(() -> {
            blocking.deleteById(id);
            return null;
        });
    }

    @Override
    public Mono<Void> delete(T entity) {
        return reactive.call(() -> {
            blocking.delete(entity);
            return null;
        });
    }

    @Override
    public Mono<Void> deleteAllById(Iterable<? extends ID> ids) {
        return reactive.call(() -> {
            blocking.deleteAllById(ids);
            return null;
        });
    }

    @Override
    public Mono<Void> deleteAll(Iterable<? extends T> entities) {
        return reactive.call(() -> {
            blocking.deleteAll(entities);
            return null;
        });
    }
}
