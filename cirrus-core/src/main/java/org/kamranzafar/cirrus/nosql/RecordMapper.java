package org.kamranzafar.cirrus.nosql;

import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.RecordComponent;
import java.lang.reflect.Type;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Maps a record to a {@link Document} and back, component by component:
 *
 * <pre>{@code
 * record Order(String customer, BigDecimal total, List<Line> lines, Status status) { }
 * RecordMapper<Order> orders = RecordMapper.of(Order.class);
 * store.put(key, orders.toDocument(order));
 * Order back = orders.fromDocument(store.get(key).orElseThrow().document());
 * }</pre>
 *
 * Supported components: strings, primitives and their boxes, {@code BigDecimal}, {@code BigInteger},
 * {@code byte[]}, enums (by name), {@code Instant} and {@code LocalDate} (ISO-8601 strings), {@code UUID},
 * nested records, and {@code List}, {@code Map<String, ?>} and {@code Optional} of those. Null and empty
 * optional components are left out of the document; missing fields read back as null or empty.
 */
public final class RecordMapper<T extends Record> {

    private final Class<T> type;
    private final RecordComponent[] components;
    private final Constructor<T> constructor;

    private RecordMapper(Class<T> type) {
        this.type = type;
        this.components = type.getRecordComponents();
        Class<?>[] types = new Class<?>[components.length];
        for (int i = 0; i < components.length; i++) {
            types[i] = components[i].getType();
        }
        try {
            this.constructor = type.getDeclaredConstructor(types);
            this.constructor.setAccessible(true);
        } catch (NoSuchMethodException e) {
            throw new IllegalArgumentException("No canonical constructor on " + type.getName(), e);
        }
    }

    public static <T extends Record> RecordMapper<T> of(Class<T> type) {
        if (!type.isRecord()) {
            throw new IllegalArgumentException(type.getName() + " is not a record");
        }
        return new RecordMapper<>(type);
    }

    public Document toDocument(T value) {
        Document.Builder b = Document.builder();
        for (RecordComponent component : components) {
            Object field;
            try {
                var accessor = component.getAccessor();
                accessor.setAccessible(true);
                field = accessor.invoke(value);
            } catch (IllegalAccessException | InvocationTargetException e) {
                throw new IllegalStateException("Cannot read " + component.getName() + " of " + type.getName(), e);
            }
            Object encoded = encode(field);
            if (encoded != null) {
                b.put(component.getName(), encoded);
            }
        }
        return b.build();
    }

    public T fromDocument(Document document) {
        Object[] args = new Object[components.length];
        for (int i = 0; i < components.length; i++) {
            RecordComponent component = components[i];
            args[i] = decode(component.getGenericType(), document.get(component.getName()), component.getName());
        }
        try {
            return constructor.newInstance(args);
        } catch (InstantiationException | IllegalAccessException e) {
            throw new IllegalStateException("Cannot create " + type.getName(), e);
        } catch (InvocationTargetException e) {
            throw new IllegalArgumentException("Cannot create " + type.getName() + ": " + e.getCause().getMessage(), e.getCause());
        }
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static Object encode(Object value) {
        return switch (value) {
            case null -> null;
            case Optional<?> o -> o.map(RecordMapper::encode).orElse(null);
            case Enum<?> e -> e.name();
            case Instant i -> i.toString();
            case LocalDate d -> d.toString();
            case UUID u -> u.toString();
            case Character c -> c.toString();
            case Record r -> RecordMapper.of((Class) r.getClass()).toDocument(r);
            case List<?> list -> list.stream().map(RecordMapper::encode).toList();
            case Map<?, ?> map -> {
                Map<String, Object> out = new LinkedHashMap<>();
                map.forEach((k, v) -> out.put(String.valueOf(k), encode(v)));
                yield out;
            }
            default -> value;
        };
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static Object decode(Type type, Object value, String path) {
        Class<?> raw = rawType(type);
        if (raw == Optional.class) {
            return Optional.ofNullable(decode(typeArgument(type, 0, path), value, path));
        }
        if (value == null) {
            if (raw.isPrimitive()) {
                throw new IllegalArgumentException(path + " is missing and " + raw + " cannot be null");
            }
            return raw == List.class ? List.of() : raw == Map.class ? Map.of() : null;
        }
        if (raw == String.class) {
            return value.toString();
        }
        if (raw == long.class || raw == Long.class) {
            return ((Number) value).longValue();
        }
        if (raw == int.class || raw == Integer.class) {
            return Math.toIntExact(((Number) value).longValue());
        }
        if (raw == short.class || raw == Short.class) {
            return ((Number) value).shortValue();
        }
        if (raw == byte.class || raw == Byte.class) {
            return ((Number) value).byteValue();
        }
        if (raw == double.class || raw == Double.class) {
            return ((Number) value).doubleValue();
        }
        if (raw == float.class || raw == Float.class) {
            return ((Number) value).floatValue();
        }
        if (raw == boolean.class || raw == Boolean.class) {
            return value;
        }
        if (raw == char.class || raw == Character.class) {
            return value.toString().charAt(0);
        }
        if (raw == BigDecimal.class) {
            return value instanceof BigDecimal bd ? bd : new BigDecimal(value.toString());
        }
        if (raw == BigInteger.class) {
            return new BigDecimal(value.toString()).toBigIntegerExact();
        }
        if (raw == byte[].class) {
            return value;
        }
        if (raw == Instant.class) {
            return Instant.parse(value.toString());
        }
        if (raw == LocalDate.class) {
            return LocalDate.parse(value.toString());
        }
        if (raw == UUID.class) {
            return UUID.fromString(value.toString());
        }
        if (raw.isEnum()) {
            return Enum.valueOf((Class) raw, value.toString());
        }
        if (raw.isRecord()) {
            return RecordMapper.of((Class) raw).fromDocument((Document) value);
        }
        if (raw == List.class) {
            Type element = typeArgument(type, 0, path);
            List<Object> out = new ArrayList<>();
            List<?> list = (List<?>) value;
            for (int i = 0; i < list.size(); i++) {
                out.add(decode(element, list.get(i), path + "[" + i + "]"));
            }
            return List.copyOf(out);
        }
        if (raw == Map.class) {
            Type valueType = typeArgument(type, 1, path);
            Map<String, Object> out = new LinkedHashMap<>();
            ((Document) value).toMap().keySet().forEach(k ->
                    out.put(k, decode(valueType, ((Document) value).get(k), path + "." + k)));
            return out;
        }
        throw new IllegalArgumentException(path + " has unsupported type " + type.getTypeName());
    }

    private static Class<?> rawType(Type type) {
        if (type instanceof Class<?> c) {
            return c;
        }
        if (type instanceof ParameterizedType p) {
            return (Class<?>) p.getRawType();
        }
        throw new IllegalArgumentException("Unsupported type " + type.getTypeName());
    }

    private static Type typeArgument(Type type, int index, String path) {
        if (type instanceof ParameterizedType p) {
            return p.getActualTypeArguments()[index];
        }
        throw new IllegalArgumentException(path + " needs type arguments: " + type.getTypeName());
    }
}
