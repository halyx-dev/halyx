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

import org.springframework.context.annotation.Profile;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/** An order's contact details, opened with the {@code customer-data} key; only with the {@code keys} feature. */
@RestController
@Profile("keys")
class ContactController {

    private final OrderContacts contacts;

    ContactController(OrderContacts contacts) {
        this.contacts = contacts;
    }

    @GetMapping("/customers/{customerId}/orders/{orderId}/contact")
    ResponseEntity<Map<String, String>> contact(@PathVariable String customerId, @PathVariable String orderId) {
        return ResponseEntity.of(contacts.find(customerId, orderId).map(email -> Map.of("email", email)));
    }
}
