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

import dev.halyx.nosql.Document;
import dev.halyx.nosql.Item;
import dev.halyx.nosql.Key;
import dev.halyx.nosql.RecordMapper;
import dev.halyx.nosql.TableConfig;
import dev.halyx.nosql.Version;
import dev.halyx.spring.data.document.DocumentTable;
import dev.halyx.spring.data.document.PartitionKey;
import dev.halyx.spring.data.document.SortKey;

import java.lang.annotation.Annotation;
import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.RecordComponent;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * How an entity record maps to an item: which components are its partition and sort keys and its version, and the
 * rest of it as a document. Built once per entity type, when its repository is created, so mistakes in the entity
 * fail at startup.
 */
final class EntityModel<T extends Record> {

    private final Class<T> type;
    private final RecordMapper<T> mapper;
    private final RecordComponent[] components;
    private final Constructor<T> constructor;
    private final RecordComponent partition;
    private final RecordComponent sort;
    private final RecordComponent version;
    private final Set<String> stored;

    private EntityModel(Class<T> type) {
        this.type = type;
        this.mapper = RecordMapper.of(type);
        this.components = type.getRecordComponents();
        this.partition = annotated(PartitionKey.class).orElseThrow(() -> new IllegalStateException(
                type.getName() + " needs a record component annotated with @PartitionKey"));
        this.sort = annotated(SortKey.class).orElse(null);
        this.version = annotated(org.springframework.data.annotation.Version.class).orElse(null);
        requireKeyType(partition);
        if (sort != null) {
            requireKeyType(sort);
        }
        if (version != null && version.getType() != Version.class) {
            throw new IllegalStateException(type.getName() + "." + version.getName()
                    + ": @Version components must be dev.halyx.nosql.Version");
        }
        Set<String> elsewhere = new HashSet<>();
        elsewhere.add(partition.getName());
        if (sort != null) {
            elsewhere.add(sort.getName());
        }
        if (version != null) {
            elsewhere.add(version.getName());
        }
        this.stored = Set.copyOf(elsewhere);
        Class<?>[] types = new Class<?>[components.length];
        for (int i = 0; i < components.length; i++) {
            types[i] = components[i].getType();
        }
        try {
            this.constructor = type.getDeclaredConstructor(types);
            this.constructor.setAccessible(true);
        } catch (NoSuchMethodException e) {
            throw new IllegalStateException("No canonical constructor on " + type.getName(), e);
        }
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    static <T> EntityModel<?> of(Class<T> type) {
        if (!type.isRecord()) {
            throw new IllegalStateException(type.getName() + " is not a record: Halyx repositories store records");
        }
        return new EntityModel<>((Class) type);
    }

    Class<T> type() {
        return type;
    }

    /** The {@code DocumentStore} bean named by {@link DocumentTable}, if any. */
    Optional<String> storeName() {
        return Optional.ofNullable(type.getAnnotation(DocumentTable.class)).map(DocumentTable::value);
    }

    /** Fails if the entity's keys don't match the table's: a sort key component exactly when the table has one. */
    void checkAgainst(TableConfig table, String storeName) {
        if (table.hasSortKey() && sort == null) {
            throw new IllegalStateException(type.getName() + " needs a @SortKey component: document store '"
                    + storeName + "' (table " + table.name() + ") has a sort key");
        }
        if (!table.hasSortKey() && sort != null) {
            throw new IllegalStateException(type.getName() + " has a @SortKey component, but document store '"
                    + storeName + "' (table " + table.name() + ") has no sort key");
        }
    }

    boolean hasSortKey() {
        return sort != null;
    }

    boolean versioned() {
        return version != null;
    }

    String partitionComponent() {
        return partition.getName();
    }

    Optional<String> sortComponent() {
        return Optional.ofNullable(sort).map(RecordComponent::getName);
    }

    /** The id type: the partition key as a String, or a Key for tables with a sort key. */
    Class<?> idType() {
        return sort == null ? String.class : Key.class;
    }

    boolean hasComponent(String name) {
        for (RecordComponent component : components) {
            if (component.getName().equals(name)) {
                return true;
            }
        }
        return false;
    }

    Key key(T entity) {
        String partitionValue = keyValue(read(entity, partition), partition.getName());
        return sort == null ? Key.of(partitionValue) : Key.of(partitionValue, keyValue(read(entity, sort), sort.getName()));
    }

    Object id(T entity) {
        Key key = key(entity);
        return sort == null ? key.partition() : key;
    }

    /** The key for an id: a partition key String, or a Key. */
    Key keyFor(Object id) {
        Objects.requireNonNull(id, "id");
        if (id instanceof Key key) {
            if (key.sort().isPresent() != (sort != null)) {
                throw new IllegalArgumentException(sort == null
                        ? type.getSimpleName() + " has no sort key, but the id " + key + " has one"
                        : type.getSimpleName() + " ids need a sort key: " + key);
            }
            return key;
        }
        if (id instanceof String partitionValue && sort == null) {
            return Key.of(partitionValue);
        }
        throw new IllegalArgumentException(type.getSimpleName() + " ids are "
                + idType().getSimpleName() + ", not " + id.getClass().getSimpleName());
    }

    Version version(T entity) {
        return version == null ? null : (Version) read(entity, version);
    }

    Document document(T entity) {
        return mapper.toDocument(entity, stored);
    }

    T entity(Item item) {
        Document.Builder document = item.document().toBuilder();
        document.put(partition.getName(), item.key().partition());
        if (sort != null) {
            document.put(sort.getName(), item.key().sort().orElseThrow());
        }
        Map<String, Object> given = new HashMap<>();
        if (version != null) {
            given.put(version.getName(), item.version());
        }
        return mapper.fromDocument(document.build(), given);
    }

    /** A copy of {@code entity} with {@code newVersion}. */
    T withVersion(T entity, Version newVersion) {
        Object[] args = new Object[components.length];
        for (int i = 0; i < components.length; i++) {
            args[i] = components[i] == version ? newVersion : read(entity, components[i]);
        }
        try {
            return constructor.newInstance(args);
        } catch (InstantiationException | IllegalAccessException e) {
            throw new IllegalStateException("Cannot create " + type.getName(), e);
        } catch (InvocationTargetException e) {
            throw new IllegalStateException("Cannot create " + type.getName() + ": " + e.getCause().getMessage(), e.getCause());
        }
    }

    /** A key value as stored: strings as they are, UUIDs and dates as ISO strings, enums by name. */
    static String keyValue(Object value, String what) {
        return switch (value) {
            case null -> throw new IllegalArgumentException(what + " must not be null");
            case String s -> s;
            case UUID u -> u.toString();
            case LocalDate d -> d.toString();
            case Enum<?> e -> e.name();
            default -> throw new IllegalArgumentException(what + " must be a String, UUID, LocalDate or enum, not "
                    + value.getClass().getSimpleName());
        };
    }

    /** A value for a secondary index attribute, as the document stores it: strings, numbers and booleans. */
    static Object indexValue(Object value, String what) {
        return switch (value) {
            case null -> throw new IllegalArgumentException(what + " must not be null");
            case String s -> s;
            case Number n -> n;
            case Boolean b -> b;
            case UUID u -> u.toString();
            case LocalDate d -> d.toString();
            case java.time.Instant i -> i.toString();
            case Enum<?> e -> e.name();
            default -> throw new IllegalArgumentException(what + ": index values must be strings, numbers, "
                    + "booleans, UUIDs, dates or enums, not " + value.getClass().getSimpleName());
        };
    }

    private Optional<RecordComponent> annotated(Class<? extends Annotation> annotation) {
        RecordComponent found = null;
        for (RecordComponent component : components) {
            if (component.isAnnotationPresent(annotation) || fieldAnnotated(component, annotation)) {
                if (found != null) {
                    throw new IllegalStateException(type.getName() + " has more than one @"
                            + annotation.getSimpleName() + " component: " + found.getName() + " and "
                            + component.getName());
                }
                found = component;
            }
        }
        return Optional.ofNullable(found);
    }

    /** Annotations that target fields only, such as Spring Data's {@code @Version}, land on the record's field. */
    private boolean fieldAnnotated(RecordComponent component, Class<? extends Annotation> annotation) {
        try {
            return type.getDeclaredField(component.getName()).isAnnotationPresent(annotation);
        } catch (NoSuchFieldException e) {
            return false;
        }
    }

    private void requireKeyType(RecordComponent component) {
        Class<?> keyType = component.getType();
        if (keyType != String.class && keyType != UUID.class && keyType != LocalDate.class && !keyType.isEnum()) {
            throw new IllegalStateException(type.getName() + "." + component.getName()
                    + ": key components must be String, UUID, LocalDate or an enum, not " + keyType.getSimpleName());
        }
    }

    private Object read(T entity, RecordComponent component) {
        try {
            var accessor = component.getAccessor();
            accessor.setAccessible(true);
            return accessor.invoke(entity);
        } catch (IllegalAccessException | InvocationTargetException e) {
            throw new IllegalStateException("Cannot read " + component.getName() + " of " + type.getName(), e);
        }
    }
}
