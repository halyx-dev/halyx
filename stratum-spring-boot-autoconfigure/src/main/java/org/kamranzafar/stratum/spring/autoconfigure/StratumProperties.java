package org.kamranzafar.stratum.spring.autoconfigure;

import org.kamranzafar.stratum.config.Credentials;
import org.kamranzafar.stratum.config.ProviderConfig;
import org.kamranzafar.stratum.interceptor.RetryPolicy;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.net.URI;
import java.time.Duration;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Stratum configuration:
 *
 * <pre>{@code
 * stratum:
 *   providers:
 *     orders:
 *       type: aws
 *       region: eu-west-2
 *   blob-stores:
 *     invoices:            # bean name
 *       provider: orders   # optional when only one provider is configured
 *       bucket: acme-invoices
 * }</pre>
 *
 * @param providers   named provider connections
 * @param blobStores  {@code BlobStore} beans to create, keyed by bean name
 * @param retry       provider-neutral retries on top of the SDKs' own
 * @param observations Micrometer observations of every operation
 */
@ConfigurationProperties("stratum")
public record StratumProperties(Map<String, Provider> providers, Map<String, BlobStoreProperties> blobStores,
                                @DefaultValue Retry retry, @DefaultValue Observations observations) {

    public StratumProperties {
        providers = ordered(providers);
        blobStores = ordered(blobStores);
    }

    private static <V> Map<String, V> ordered(Map<String, V> map) {
        return map == null ? Map.of() : Collections.unmodifiableMap(new LinkedHashMap<>(map));
    }

    /**
     * One provider connection.
     *
     * @param type        provider id: {@code aws}, {@code azure}, {@code gcp} or {@code memory}. The
     *                    matching {@code stratum-<type>} module must be on the classpath
     * @param region      cloud region, where the provider uses one
     * @param endpoint    endpoint override, for emulators and private endpoints
     * @param credentials how to authenticate; the platform default chain when omitted
     * @param properties  provider-specific settings, such as {@code aws.s3.path-style} or
     *                    {@code gcp.project-id}
     */
    public record Provider(String type, String region, URI endpoint, @DefaultValue CredentialsProperties credentials,
                           Map<String, String> properties) {

        public Provider {
            properties = ordered(properties);
        }

        ProviderConfig toProviderConfig(String name) {
            if (type == null || type.isBlank()) {
                throw new IllegalStateException("stratum.providers." + name + ".type is required (aws, azure, gcp or memory)");
            }
            ProviderConfig config = ProviderConfig.of(type).credentials(credentials.toCredentials(name));
            if (region != null) {
                config = config.region(region);
            }
            if (endpoint != null) {
                config = config.endpoint(endpoint);
            }
            for (var property : properties.entrySet()) {
                config = config.property(property.getKey(), property.getValue());
            }
            return config;
        }
    }

    /**
     * @param type   {@code default} (platform credential chain), {@code static} (id and secret, such as an
     *               AWS access key or an Azure account name and key) or {@code anonymous}
     */
    public record CredentialsProperties(@DefaultValue("default") Type type, String id, String secret) {

        public enum Type { DEFAULT, STATIC, ANONYMOUS }

        Credentials toCredentials(String provider) {
            return switch (type) {
                case DEFAULT -> Credentials.defaultChain();
                case ANONYMOUS -> Credentials.anonymous();
                case STATIC -> {
                    if (id == null || secret == null) {
                        throw new IllegalStateException("stratum.providers." + provider
                                + ".credentials: static credentials need both id and secret");
                    }
                    yield Credentials.of(id, secret);
                }
            };
        }
    }

    /**
     * @param provider the provider name; may be omitted when exactly one provider is configured
     * @param bucket   bucket or container name; defaults to the bean name
     */
    public record BlobStoreProperties(String provider, String bucket) {
    }

    /**
     * @param enabled        whether to retry throttled and transiently failing idempotent operations
     * @param maxAttempts    total attempts including the first
     * @param initialBackoff upper bound of the randomised delay before the first retry
     * @param maxBackoff     cap on the delay between attempts
     * @param multiplier     growth factor of the delay bound per attempt
     */
    public record Retry(@DefaultValue("false") boolean enabled, @DefaultValue("3") int maxAttempts,
                        @DefaultValue("100ms") Duration initialBackoff, @DefaultValue("2s") Duration maxBackoff,
                        @DefaultValue("2.0") double multiplier) {

        RetryPolicy toPolicy() {
            return new RetryPolicy(maxAttempts, initialBackoff, maxBackoff, multiplier);
        }
    }

    /**
     * @param enabled whether to record a {@code stratum.operation} observation (timer and span) per
     *                operation, when Micrometer is present
     */
    public record Observations(@DefaultValue("true") boolean enabled) {
    }
}
