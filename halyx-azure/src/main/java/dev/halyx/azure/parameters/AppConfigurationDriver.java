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

import com.azure.core.exception.HttpResponseException;
import com.azure.data.appconfiguration.ConfigurationAsyncClient;
import com.azure.data.appconfiguration.ConfigurationClient;
import com.azure.data.appconfiguration.models.ConfigurationSetting;
import com.azure.data.appconfiguration.models.SettingSelector;
import dev.halyx.azure.AzureErrors;
import dev.halyx.azure.AzureProvider;
import dev.halyx.capability.Capabilities;
import dev.halyx.error.CloudException;
import dev.halyx.interceptor.AsyncInvocation;
import dev.halyx.parameters.Parameter;
import dev.halyx.parameters.spi.AbstractParameterDriver;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;

/**
 * Parameters on Azure App Configuration. Portable names are keys as they are; an optional label
 * selects one variant (such as an environment). The version is the setting's ETag: App Configuration
 * keeps revision history but cannot read a value back by version, so VERSIONS is not supported.
 */
public final class AppConfigurationDriver extends AbstractParameterDriver {

    private final ConfigurationClient client;
    private final ConfigurationAsyncClient asyncClient;
    private final String label;

    public AppConfigurationDriver(ConfigurationClient client, ConfigurationAsyncClient asyncClient, String label,
                                  boolean nativeNames) {
        super(AzureProvider.ID, Capabilities.none(), nativeNames);
        this.client = client;
        this.asyncClient = asyncClient;
        this.label = label;
    }

    @Override
    public Optional<Parameter> get(String name) {
        try {
            return Optional.ofNullable(client.getConfigurationSetting(name, label)).map(AppConfigurationDriver::toPortable);
        } catch (HttpResponseException e) {
            if (isNotFound(e)) {
                return Optional.empty();
            }
            throw e;
        }
    }

    @Override
    public Map<String, Parameter> list(String prefix) {
        Map<String, Parameter> found = new LinkedHashMap<>();
        client.listConfigurationSettings(selector(prefix)).forEach(setting -> {
            if (matches(setting.getKey(), prefix)) {
                found.put(setting.getKey(), toPortable(setting));
            }
        });
        return found;
    }

    @Override
    public Parameter put(String name, String value) {
        return toPortable(client.setConfigurationSetting(name, label, value));
    }

    @Override
    public void delete(String name) {
        try {
            client.deleteConfigurationSetting(name, label);
        } catch (HttpResponseException e) {
            if (!isNotFound(e)) {
                throw e;
            }
        }
    }

    private SettingSelector selector(String prefix) {
        return new SettingSelector().setKeyFilter(prefix.isEmpty() ? "*" : prefix + "*")
                .setLabelFilter(label == null ? "\0" : label);
    }

    @Override
    public CompletableFuture<Optional<Parameter>> getAsync(String name, Executor blocking) {
        return asyncClient.getConfigurationSetting(name, label).toFuture()
                .thenApply(setting -> Optional.ofNullable(setting).map(AppConfigurationDriver::toPortable))
                .exceptionallyCompose(error -> notFound(error) ? CompletableFuture.completedFuture(Optional.empty())
                        : CompletableFuture.failedFuture(error));
    }

    @Override
    public CompletableFuture<Map<String, Parameter>> listAsync(String prefix, Executor blocking) {
        return asyncClient.listConfigurationSettings(selector(prefix)).filter(setting -> matches(setting.getKey(), prefix))
                .collect(LinkedHashMap<String, Parameter>::new, (found, setting) -> found.put(setting.getKey(), toPortable(setting)))
                .<Map<String, Parameter>>map(found -> found).toFuture();
    }

    @Override
    public CompletableFuture<Parameter> putAsync(String name, String value, Executor blocking) {
        return asyncClient.setConfigurationSetting(name, label, value).toFuture().thenApply(AppConfigurationDriver::toPortable);
    }

    @Override
    public CompletableFuture<Void> deleteAsync(String name, Executor blocking) {
        // With the response: deleting a missing key answers 204 with no setting, which the plain call can't map.
        return asyncClient.deleteConfigurationSettingWithResponse(new ConfigurationSetting().setKey(name).setLabel(label), false)
                .toFuture().<Void>thenApply(response -> null)
                .exceptionallyCompose(error -> notFound(error) ? CompletableFuture.completedFuture(null)
                        : CompletableFuture.failedFuture(error));
    }

    private static boolean notFound(Throwable error) {
        return AsyncInvocation.unwrap(error) instanceof HttpResponseException e && isNotFound(e);
    }

    /** The App Configuration SDK reports missing keys as a plain 404 response exception. */
    private static boolean isNotFound(HttpResponseException e) {
        return e.getResponse() != null && e.getResponse().getStatusCode() == 404;
    }

    private static Parameter toPortable(ConfigurationSetting setting) {
        var modified = setting.getLastModified();
        return new Parameter(setting.getKey(), setting.getValue(), setting.getETag() == null ? "" : setting.getETag(),
                modified == null ? null : modified.toInstant());
    }

    @Override
    protected Optional<CloudException> translateNative(Throwable error) {
        return AzureErrors.translate(error);
    }

    @Override
    protected List<Object> nativeClients() {
        return List.of(client, asyncClient);
    }
}
