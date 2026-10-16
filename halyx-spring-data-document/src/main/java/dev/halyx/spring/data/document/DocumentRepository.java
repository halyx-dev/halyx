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

import org.springframework.data.repository.NoRepositoryBean;
import org.springframework.data.repository.Repository;

import java.util.List;
import java.util.Optional;

/**
 * A Spring Data repository over a Halyx document store. Entities are records with a {@link PartitionKey}
 * component and, for tables with a sort key, a {@link SortKey} component. The id is the partition key as a
 * {@code String} for tables without a sort key, and a {@code dev.halyx.nosql.Key} for tables with one.
 *
 * <p>With a component of type {@code dev.halyx.nosql.Version} annotated with Spring Data's {@code @Version},
 * writes are optimistic: saving an entity without a version inserts it only if the id is free
 * ({@code DuplicateKeyException} otherwise), and saving or deleting one with a version succeeds only if the stored
 * item still has it ({@code OptimisticLockingFailureException} otherwise). {@code save} returns the entity with its
 * new version.
 *
 * <p>Query methods are derived from their names, on the partition key with an optional sort-key condition, or on
 * a declared secondary index; see the Spring Data guide. There is no {@code findAll} or {@code count}: both would
 * read the whole table.
 *
 * @param <T>  the entity record
 * @param <ID> {@code String} or {@code dev.halyx.nosql.Key}
 */
@NoRepositoryBean
public interface DocumentRepository<T, ID> extends Repository<T, ID> {

    <S extends T> S save(S entity);

    <S extends T> List<S> saveAll(Iterable<S> entities);

    Optional<T> findById(ID id);

    boolean existsById(ID id);

    /** The entities that exist, in the order of {@code ids}. */
    List<T> findAllById(Iterable<ID> ids);

    void deleteById(ID id);

    /** Deletes the entity; with a version, only if the stored item still has it. */
    void delete(T entity);

    void deleteAllById(Iterable<? extends ID> ids);

    void deleteAll(Iterable<? extends T> entities);
}
