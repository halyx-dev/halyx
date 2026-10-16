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
package dev.halyx.spring.reactiverepository;

import dev.halyx.nosql.Key;
import dev.halyx.spring.data.document.DocumentRepository;
import dev.halyx.spring.repository.Shipment;

import java.util.List;

/** A blocking repository next to the reactive one, in the same scanned package. */
public interface BlockingShipmentRepository extends DocumentRepository<Shipment, Key> {
    List<Shipment> findByWarehouseOrderByTrackingIdDesc(String warehouse);
}
