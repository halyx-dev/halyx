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

import dev.halyx.nosql.Key;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.util.Optional;
import java.util.regex.Pattern;

/**
 * The {@code keys} feature: contact details kept with each order, sealed with the {@code customer-data} key from the
 * cloud's key service (AWS KMS, Azure Key Vault or Google Cloud KMS). Without the feature, orders take no contact
 * details, so personal data is never stored in the clear.
 */
@Component
@Profile("keys")
class OrderContacts {

    /** The {@code halyx.keys} bean that seals contact details and receipts. */
    static final String KEY = "customer-data";

    /** Enough to keep obvious mistakes out; deliverability is the mail service's to decide. */
    private static final Pattern EMAIL = Pattern.compile("[^@\\s]{1,64}@[^@\\s]{1,189}");

    private final OrderContactRepository contacts;

    OrderContacts(OrderContactRepository contacts) {
        this.contacts = contacts;
    }

    static void validate(String email) {
        if (!EMAIL.matcher(email).matches()) {
            throw new InvalidOrderException("a contact is an email address");
        }
    }

    /** One key service call: the email's data key is wrapped once. */
    void save(String customerId, String orderId, String email) {
        contacts.save(new OrderContact(OrderContact.partition(customerId), orderId, email));
    }

    /** One key service call to open the email. */
    Optional<String> find(String customerId, String orderId) {
        return contacts.findById(Key.of(OrderContact.partition(customerId), orderId)).map(OrderContact::email);
    }
}
