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
package org.kamranzafar.halyx.storage;

/**
 * Portable storage tier: trades storage price against access cost and latency.
 *
 * <table>
 *   <caption>Native classes</caption>
 *   <tr><th>Tier</th><th>AWS S3</th><th>Azure Blob</th><th>GCS</th></tr>
 *   <tr><td>HOT</td><td>STANDARD</td><td>Hot</td><td>STANDARD</td></tr>
 *   <tr><td>COOL</td><td>STANDARD_IA</td><td>Cool</td><td>NEARLINE</td></tr>
 *   <tr><td>COLD</td><td>GLACIER_IR</td><td>Cold</td><td>COLDLINE</td></tr>
 *   <tr><td>ARCHIVE</td><td>GLACIER</td><td>Archive</td><td>ARCHIVE</td></tr>
 * </table>
 *
 * <p>All tiers except {@link #ARCHIVE} are readable immediately everywhere. ARCHIVE blobs on S3 and
 * Azure are offline and must be restored before reading; on GCS they stay readable.
 */
public enum StorageTier {
    HOT,
    COOL,
    COLD,
    ARCHIVE
}
