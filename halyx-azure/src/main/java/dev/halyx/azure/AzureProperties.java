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
package dev.halyx.azure;

/** Azure-specific keys for {@link dev.halyx.config.ProviderConfig#property(String, String)}. */
public final class AzureProperties {

    /**
     * Storage account name. Used to derive the endpoint {@code https://<account>.blob.core.windows.net}
     * when no endpoint override is set. Defaults to the id of {@code Credentials.Static}.
     */
    public static final String STORAGE_ACCOUNT = "azure.storage.account";

    /**
     * Stream cluster property: the Entra ID scope of Event Hubs tokens, when it isn't the namespace of the first
     * bootstrap server ({@code https://<namespace>.servicebus.windows.net/.default}).
     */
    public static final String EVENT_HUBS_SCOPE = "azure.event-hubs.scope";

    /** Cosmos DB account endpoint, {@code https://<account>.documents.azure.com:443/}. */
    public static final String COSMOS_ENDPOINT = "azure.cosmos.endpoint";

    /** Cosmos DB account key; without it, Entra ID with the default credential chain. */
    public static final String COSMOS_KEY = "azure.cosmos.key";

    /** Cosmos DB database holding the containers (required for document stores). */
    public static final String COSMOS_DATABASE = "azure.cosmos.database";

    /** Service Bus connection string (shared access key, or the local emulator's). */
    public static final String SERVICE_BUS_CONNECTION_STRING = "azure.servicebus.connection-string";

    /**
     * Service Bus namespace, {@code <name>.servicebus.windows.net}, for Entra ID login with the default
     * credential chain; used when no connection string is set.
     */
    public static final String SERVICE_BUS_NAMESPACE = "azure.servicebus.namespace";

    /** Topic property: {@code queue} to send to a queue rather than a topic ({@code topic}, the default). */
    public static final String SERVICE_BUS_ENTITY = "azure.servicebus.entity";

    /**
     * Topic and subscription property: {@code true} for session-enabled entities, which turns ordering keys
     * into session ids and receives session by session.
     */
    public static final String SERVICE_BUS_SESSIONS = "azure.servicebus.sessions";

    /** Subscription property: sessions a session subscription holds at once (default 8). */
    public static final String SERVICE_BUS_MAX_SESSIONS = "azure.servicebus.max-sessions";

    /** Largest message body in bytes: 262144 (256 KiB) on the standard tier, the default; up to 100 MB on premium. */
    public static final String SERVICE_BUS_MAX_MESSAGE_BYTES = "azure.servicebus.max-message-bytes";

    /**
     * Event bus and subscription property: the Event Grid namespace's endpoint, such as
     * {@code https://orders.australiaeast-1.eventgrid.azure.net}. On an event bus, the bus name is the namespace
     * topic; on a subscription, the name is the pull subscription and {@code topic} its namespace topic.
     */
    public static final String EVENT_GRID_ENDPOINT = "azure.eventgrid.endpoint";

    /** Event bus and subscription property: an access key, instead of Entra ID login. */
    public static final String EVENT_GRID_ACCESS_KEY = "azure.eventgrid.access-key";

    /** Subscription property: the pull subscription's lock duration, ISO-8601 (default PT60S, Event Grid's). */
    public static final String EVENT_GRID_LOCK_DURATION = "azure.eventgrid.lock-duration";

    private AzureProperties() {
    }
}
