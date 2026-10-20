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

import dev.halyx.json.Json;

import java.util.LinkedHashMap;
import java.util.Map;

/** The JSON body of the order-placed message, event and stream record: the order's key and total. */
final class OrderMessages {

    private OrderMessages() {
    }

    static String placed(Order order) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("customerId", order.customerId());
        body.put("orderId", order.orderId());
        body.put("total", order.total().toPlainString());
        body.put("currency", order.currency());
        return Json.write(body);
    }

    /** The customer and order ids in a {@link #placed} body. */
    static String[] key(String json) {
        Map<String, Object> body = Json.parseObject(json)
                .orElseThrow(() -> new IllegalArgumentException("not an order message: " + json));
        return new String[] {(String) body.get("customerId"), (String) body.get("orderId")};
    }
}
