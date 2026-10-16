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

import dev.halyx.error.PreconditionFailedException;
import dev.halyx.nosql.Document;
import dev.halyx.nosql.DocumentStore;
import dev.halyx.nosql.Item;
import dev.halyx.nosql.Key;
import dev.halyx.nosql.Version;
import dev.halyx.nosql.WriteOptions;
import dev.halyx.spring.data.document.DocumentRepository;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.dao.OptimisticLockingFailureException;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * The base implementation of every {@link DocumentRepository}: CRUD by id on one document store, with optimistic
 * writes for versioned entities.
 */
public class SimpleDocumentRepository<T extends Record, ID> implements DocumentRepository<T, ID> {

    private final DocumentStore store;
    private final EntityModel<T> model;

    SimpleDocumentRepository(DocumentStore store, EntityModel<T> model) {
        this.store = store;
        this.model = model;
    }

    /** The document store, for custom repository fragments. */
    public DocumentStore store() {
        return store;
    }

    @Override
    public <S extends T> S save(S entity) {
        Objects.requireNonNull(entity, "entity");
        Key key = model.key(entity);
        Version version = model.version(entity);
        WriteOptions options = !model.versioned() ? WriteOptions.defaults()
                : version == null ? WriteOptions.ifNotExists() : WriteOptions.ifVersion(version);
        Item written;
        try {
            written = store.put(key, model.document(entity), options);
        } catch (PreconditionFailedException e) {
            throw conflict(key, version, e);
        }
        @SuppressWarnings("unchecked")
        S saved = model.versioned() ? (S) model.withVersion(entity, written.version()) : entity;
        return saved;
    }

    @Override
    public <S extends T> List<S> saveAll(Iterable<S> entities) {
        Objects.requireNonNull(entities, "entities");
        if (model.versioned()) {
            List<S> saved = new ArrayList<>();
            entities.forEach(entity -> saved.add(save(entity)));
            return saved;
        }
        // Unversioned items need no conditions, so they go in batches.
        List<S> all = new ArrayList<>();
        Map<Key, Document> items = new LinkedHashMap<>();
        for (S entity : entities) {
            all.add(Objects.requireNonNull(entity, "entity"));
            items.put(model.key(entity), model.document(entity));
        }
        if (!items.isEmpty()) {
            store.putAll(items);
        }
        return all;
    }

    @Override
    public Optional<T> findById(ID id) {
        return store.get(model.keyFor(id)).map(model::entity);
    }

    @Override
    public boolean existsById(ID id) {
        return store.get(model.keyFor(id)).isPresent();
    }

    @Override
    public List<T> findAllById(Iterable<ID> ids) {
        Objects.requireNonNull(ids, "ids");
        List<Key> keys = new ArrayList<>();
        ids.forEach(id -> keys.add(model.keyFor(id)));
        if (keys.isEmpty()) {
            return List.of();
        }
        Map<Key, Item> found = store.getAll(keys);
        List<T> entities = new ArrayList<>();
        for (Key key : keys) {
            Item item = found.get(key);
            if (item != null) {
                entities.add(model.entity(item));
            }
        }
        return entities;
    }

    @Override
    public void deleteById(ID id) {
        store.delete(model.keyFor(id));
    }

    @Override
    public void delete(T entity) {
        Objects.requireNonNull(entity, "entity");
        Key key = model.key(entity);
        Version version = model.version(entity);
        try {
            store.delete(key, version);
        } catch (PreconditionFailedException e) {
            throw conflict(key, version, e);
        }
    }

    @Override
    public void deleteAllById(Iterable<? extends ID> ids) {
        Objects.requireNonNull(ids, "ids");
        ids.forEach(this::deleteById);
    }

    @Override
    public void deleteAll(Iterable<? extends T> entities) {
        Objects.requireNonNull(entities, "entities");
        entities.forEach(this::delete);
    }

    private RuntimeException conflict(Key key, Version version, PreconditionFailedException cause) {
        String entity = model.type().getSimpleName();
        if (version == null) {
            return new DuplicateKeyException(entity + " " + key + " already exists", cause);
        }
        return new OptimisticLockingFailureException(entity + " " + key + " is no longer at version " + version
                + ": it changed or was deleted since it was read", cause);
    }
}
