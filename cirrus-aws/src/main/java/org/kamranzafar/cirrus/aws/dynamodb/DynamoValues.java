package org.kamranzafar.cirrus.aws.dynamodb;

import org.kamranzafar.cirrus.nosql.Document;
import software.amazon.awssdk.core.SdkBytes;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Converts between portable values and DynamoDB attribute values. Numbers are stored as N; on the way back,
 * whole numbers that fit become {@code Long}, decimals a {@code Double} when that is exact and a
 * {@code BigDecimal} otherwise.
 */
final class DynamoValues {

    private DynamoValues() {
    }

    static AttributeValue toAttribute(Object value) {
        return switch (value) {
            case null -> AttributeValue.fromNul(true);
            case String s -> AttributeValue.fromS(s);
            case Boolean b -> AttributeValue.fromBool(b);
            case Long l -> AttributeValue.fromN(l.toString());
            case Double d -> AttributeValue.fromN(BigDecimal.valueOf(d).toPlainString());
            case BigDecimal bd -> AttributeValue.fromN(bd.toPlainString());
            case byte[] bytes -> AttributeValue.fromB(SdkBytes.fromByteArray(bytes));
            case Document d -> AttributeValue.fromM(toAttributes(d, Set.of()));
            case List<?> list -> AttributeValue.fromL(list.stream().map(DynamoValues::toAttribute).toList());
            default -> throw new IllegalArgumentException("Unsupported value " + value.getClass());
        };
    }

    static Map<String, AttributeValue> toAttributes(Document document, Set<String> skip) {
        Map<String, AttributeValue> out = new LinkedHashMap<>();
        for (String field : document.fieldNames()) {
            if (!skip.contains(field)) {
                out.put(field, toAttribute(document.get(field)));
            }
        }
        return out;
    }

    static Object fromAttribute(AttributeValue value) {
        return switch (value.type()) {
            case S -> value.s();
            case N -> number(value.n());
            case BOOL -> value.bool();
            case NUL -> null;
            case B -> value.b().asByteArray();
            case M -> fromAttributes(value.m(), Set.of());
            case L -> {
                List<Object> out = new ArrayList<>();
                value.l().forEach(v -> out.add(fromAttribute(v)));
                yield out;
            }
            case SS -> List.copyOf(value.ss());
            case NS -> value.ns().stream().map(DynamoValues::number).toList();
            case BS -> value.bs().stream().map(b -> (Object) b.asByteArray()).toList();
            case UNKNOWN_TO_SDK_VERSION -> throw new IllegalArgumentException("Unknown DynamoDB attribute type");
        };
    }

    static Document fromAttributes(Map<String, AttributeValue> attributes, Set<String> skip) {
        Document.Builder b = Document.builder();
        attributes.forEach((k, v) -> {
            if (!skip.contains(k)) {
                b.put(k, fromAttribute(v));
            }
        });
        return b.build();
    }

    static Object number(String text) {
        BigDecimal value = new BigDecimal(text);
        if (value.scale() <= 0 || value.stripTrailingZeros().scale() <= 0) {
            try {
                return value.longValueExact();
            } catch (ArithmeticException tooBig) {
                return value;
            }
        }
        double d = value.doubleValue();
        return !Double.isInfinite(d) && BigDecimal.valueOf(d).compareTo(value) == 0 ? (Object) d : value;
    }
}
