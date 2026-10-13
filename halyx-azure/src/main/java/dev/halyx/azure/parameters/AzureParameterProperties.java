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
package dev.halyx.azure.parameters;

/** Azure parameter store properties ({@code ParameterStoreConfig.property}). */
public final class AzureParameterProperties {

    /** App Configuration connection string ({@code Endpoint=...;Id=...;Secret=...}) instead of Entra ID. */
    public static final String APPCONFIG_CONNECTION_STRING = "azure.appconfig.connection-string";

    /** App Configuration label to read and write; the null label when unset. */
    public static final String APPCONFIG_LABEL = "azure.appconfig.label";

    /** {@code true} for local emulators only: send requests over plain HTTP. */
    public static final String INSECURE_HTTP = "azure.emulator.insecure-http";

    private AzureParameterProperties() {
    }
}
