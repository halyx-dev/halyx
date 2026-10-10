package org.kamranzafar.cirrus.nosql;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RecordMapperTest {

    enum Status { OPEN, SHIPPED }

    record Line(String sku, int quantity, BigDecimal price) {
    }

    record Order(UUID id, String customer, Status status, List<Line> lines, Map<String, String> notes,
                 Instant placed, LocalDate delivery, Optional<String> coupon, boolean gift, long version,
                 double weight, byte[] signature) {
    }

    private final RecordMapper<Order> mapper = RecordMapper.of(Order.class);

    @Test
    void roundTripsNestedRecordsCollectionsAndScalars() {
        Order order = new Order(UUID.randomUUID(), "acme", Status.SHIPPED,
                List.of(new Line("A-1", 2, new BigDecimal("9.99")), new Line("B-2", 1, new BigDecimal("100"))),
                Map.of("gate", "B"), Instant.parse("2026-10-11T08:00:00Z"), LocalDate.of(2026, 10, 14),
                Optional.of("WELCOME"), true, 7, 1.5, new byte[] {1, 2});

        Document document = mapper.toDocument(order);
        Order back = mapper.fromDocument(document);

        assertThat(document.getString("status")).hasValue("SHIPPED");
        assertThat(document.getString("placed")).hasValue("2026-10-11T08:00:00Z");
        assertThat(back).usingRecursiveComparison().isEqualTo(order);
    }

    @Test
    void absentFieldsBecomeNullOrEmpty() {
        Order order = mapper.fromDocument(Document.builder().put("customer", "x").put("gift", false)
                .put("version", 1).put("weight", 0.0).build());

        assertThat(order.customer()).isEqualTo("x");
        assertThat(order.coupon()).isEmpty();
        assertThat(order.lines()).isEmpty();
        assertThat(order.id()).isNull();
        assertThat(mapper.toDocument(order).has("coupon")).isFalse();
    }

    @Test
    void missingPrimitiveFails() {
        assertThatThrownBy(() -> mapper.fromDocument(Document.empty())).hasMessageContaining("cannot be null");
    }
}
