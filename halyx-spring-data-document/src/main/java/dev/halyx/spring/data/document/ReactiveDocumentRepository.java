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
package dev.halyx.spring.data.document;

import org.reactivestreams.Publisher;
import org.springframework.data.repository.NoRepositoryBean;
import org.springframework.data.repository.Repository;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * A reactive Spring Data repository over a document store: {@link DocumentRepository}'s operations as
 * {@code Mono} and {@code Flux}, run on {@code halyx-reactor}'s scheduler. Derived query methods return a
 * {@code Flux} of entities, a {@code Mono} of one, a {@code Mono<Boolean>} for {@code existsBy...}, or a
 * {@code Mono<Window>} with a {@code ScrollPosition}, on the same key and index shapes as the blocking
 * repositories:
 *
 * <pre>{@code
 * interface Orders extends ReactiveDocumentRepository<Order, OrderId> {
 *     Flux<Order> findByCustomerIdOrderByOrderIdDesc(String customerId);   // read page by page as asked
 *     Mono<Order> findFirstByCustomerIdOrderByOrderIdDesc(String customerId);
 *     Mono<Boolean> existsByStatus(Status status);
 * }
 * }</pre>
 *
 * @param <T>  the entity, a record
 * @param <ID> its id: the partition key, or a record of the partition and sort keys
 */
@NoRepositoryBean
public interface ReactiveDocumentRepository<T, ID> extends Repository<T, ID> {

    /** Saves the entity; with a version, only if the stored item still has it (see {@link DocumentRepository#save}). */
    <S extends T> Mono<S> save(S entity);

    <S extends T> Flux<S> saveAll(Iterable<S> entities);

    /** Saves each entity as it arrives, one at a time, in order. */
    <S extends T> Flux<S> saveAll(Publisher<S> entities);

    /** The entity, or empty. */
    Mono<T> findById(ID id);

    Mono<Boolean> existsById(ID id);

    /** The entities that exist, in the order of {@code ids}. */
    Flux<T> findAllById(Iterable<ID> ids);

    Mono<Void> deleteById(ID id);

    /** Deletes the entity; with a version, only if the stored item still has it. */
    Mono<Void> delete(T entity);

    Mono<Void> deleteAllById(Iterable<? extends ID> ids);

    Mono<Void> deleteAll(Iterable<? extends T> entities);
}
