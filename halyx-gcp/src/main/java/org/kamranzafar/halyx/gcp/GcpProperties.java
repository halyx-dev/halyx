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
package org.kamranzafar.halyx.gcp;

/** GCP-specific keys for {@link org.kamranzafar.halyx.config.ProviderConfig#property(String, String)}. */
public final class GcpProperties {

    /** Google Cloud project id. Defaults to the project of the Application Default Credentials. */
    public static final String PROJECT_ID = "gcp.project-id";

    /** Firestore database id (default {@code (default)}). */
    public static final String FIRESTORE_DATABASE = "gcp.firestore.database";

    /**
     * SQL property: connect through the Cloud SQL Java connector to this instance
     * ({@code project:region:instance}) instead of to a host. The connector must be on the classpath.
     */
    public static final String SQL_INSTANCE = "gcp.sql.instance";

    private GcpProperties() {
    }
}
