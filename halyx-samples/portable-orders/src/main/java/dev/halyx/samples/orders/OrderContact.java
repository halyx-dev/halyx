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

import dev.halyx.spring.data.document.DocumentTable;
import dev.halyx.spring.data.document.Encrypted;
import dev.halyx.spring.data.document.PartitionKey;
import dev.halyx.spring.data.document.SortKey;

/**
 * Where to reach the customer about an order ({@code keys} feature), in the {@code orders} table next to the orders,
 * under its own partition, {@code contact#<customer id>}, so listing a customer's orders never reads it. The email is
 * personal data, so it is sealed with the {@code customer-data} key before it reaches the table: the database holds
 * HLXS sealed data bound to the item and the field, never the address.
 */
@DocumentTable("orders")
public record OrderContact(@PartitionKey String contactOf, @SortKey String orderId,
                           @Encrypted(OrderContacts.KEY) String email) {

    static String partition(String customerId) {
        return "contact#" + customerId;
    }
}
