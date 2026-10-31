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
package dev.halyx.gcp;

/** GCP-specific keys for {@link dev.halyx.config.ProviderConfig#property(String, String)}. */
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

    /**
     * Stream cluster property: the email of the principal Managed Kafka tokens are for, when the credentials don't
     * name one (a gcloud user login). Service accounts are detected.
     */
    public static final String KAFKA_PRINCIPAL = "gcp.kafka.principal";

    /**
     * Event bus property: the Eventarc Advanced message bus's location, such as {@code australia-southeast1}, when
     * the bus is named by its id rather than {@code projects/<p>/locations/<l>/messageBuses/<id>}.
     */
    public static final String EVENTARC_LOCATION = "gcp.eventarc.location";

    /**
     * Function property: the function's region, such as {@code europe-west2}, for its default URL
     * {@code https://<region>-<project>.cloudfunctions.net/<name>}.
     */
    public static final String FUNCTIONS_REGION = "gcp.functions.region";

    /** Function property: the function's URL, such as a {@code run.app} URL, instead of the default one. */
    public static final String FUNCTIONS_URL = "gcp.functions.url";

    /** Function property: the audience of the ID tokens sent to the function, when it isn't the function's URL. */
    public static final String FUNCTIONS_AUDIENCE = "gcp.functions.audience";

    private GcpProperties() {
    }
}
