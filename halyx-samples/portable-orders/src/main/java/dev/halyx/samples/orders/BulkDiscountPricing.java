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
package dev.halyx.samples.orders;

import org.kamranzafar.flipjack.Toggleable;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;

/**
 * 10% off each line of {@value #MIN_QUANTITY} or more, behind the {@code bulk-discount} feature flag. While the flag is
 * off, or missing from the settings store, calls go to {@link StandardPricing}. See {@link FeatureFlags}.
 */
@Component
@Toggleable(value = FeatureFlags.BULK_DISCOUNT, fallback = StandardPricing.class)
class BulkDiscountPricing implements OrderPricing {

    static final int MIN_QUANTITY = 10;
    private static final BigDecimal DISCOUNTED = new BigDecimal("0.90");

    @Override
    public BigDecimal total(List<OrderLine> lines) {
        return lines.stream()
                .map(line -> {
                    BigDecimal price = line.unitPrice().multiply(BigDecimal.valueOf(line.quantity()));
                    return line.quantity() >= MIN_QUANTITY ? price.multiply(DISCOUNTED) : price;
                })
                .reduce(BigDecimal.ZERO, BigDecimal::add)
                .setScale(2, RoundingMode.HALF_UP);
    }
}
