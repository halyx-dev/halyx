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
package org.kamranzafar.halyx.azure.parameters;

import com.azure.core.exception.HttpResponseException;
import com.azure.data.appconfiguration.ConfigurationClient;
import com.azure.data.appconfiguration.models.ConfigurationSetting;
import com.azure.data.appconfiguration.models.SettingSelector;
import org.kamranzafar.halyx.azure.AzureErrors;
import org.kamranzafar.halyx.azure.AzureProvider;
import org.kamranzafar.halyx.capability.Capabilities;
import org.kamranzafar.halyx.error.CloudException;
import org.kamranzafar.halyx.parameters.Parameter;
import org.kamranzafar.halyx.parameters.spi.AbstractParameterDriver;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Parameters on Azure App Configuration. Portable names are keys as they are; an optional label
 * selects one variant (such as an environment). The version is the setting's ETag: App Configuration
 * keeps revision history but cannot read a value back by version, so VERSIONS is not supported.
 */
public final class AppConfigurationDriver extends AbstractParameterDriver {

    private final ConfigurationClient client;
    private final String label;

    public AppConfigurationDriver(ConfigurationClient client, String label, boolean nativeNames) {
        super(AzureProvider.ID, Capabilities.none(), nativeNames);
        this.client = client;
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
        SettingSelector selector = new SettingSelector()
                .setKeyFilter(prefix.isEmpty() ? "*" : prefix + "*")
                .setLabelFilter(label == null ? "\0" : label);
        Map<String, Parameter> found = new LinkedHashMap<>();
        client.listConfigurationSettings(selector).forEach(setting -> {
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
        return List.of(client);
    }
}
