package org.kamranzafar.stratum.spring.autoconfigure;

import org.kamranzafar.stratum.cache.CacheAuth;
import org.kamranzafar.stratum.cache.CacheConfig;
import org.kamranzafar.stratum.config.Credentials;
import org.kamranzafar.stratum.config.ProviderConfig;
import org.kamranzafar.stratum.interceptor.RetryPolicy;
import org.kamranzafar.stratum.parameters.ParameterKind;
import org.kamranzafar.stratum.parameters.ParameterStoreConfig;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.net.URI;
import java.time.Duration;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
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
 *   caches:
 *     sessions:            # bean name
 *       endpoint: rediss://master.sessions.abc123.euw2.cache.amazonaws.com:6379
 *       default-ttl: 30m
 *       auth: { type: iam, username: sessions-app }
 *       properties: { aws.elasticache.cache-name: sessions }
 * }</pre>
 *
 * @param providers   named provider connections
 * @param blobStores  {@code BlobStore} beans to create, keyed by bean name
 * @param caches      {@code Cache<String>} beans to create, keyed by bean name
 * @param cacheManager a Spring {@code CacheManager} for {@code @Cacheable}, enabled by setting its endpoint
 * @param parameterStores {@code ParameterStore} beans to create, keyed by bean name; also the names used by
 *                    {@code spring.config.import=stratum:<name>}
 * @param retry       provider-neutral retries on top of the SDKs' own
 * @param observations Micrometer observations of every operation
 */
@ConfigurationProperties("stratum")
public record StratumProperties(Map<String, Provider> providers, Map<String, BlobStoreProperties> blobStores,
                                Map<String, CacheProperties> caches, @DefaultValue CacheManagerProperties cacheManager,
                                Map<String, ParameterStoreProperties> parameterStores,
                                @DefaultValue Retry retry, @DefaultValue Observations observations) {

    public StratumProperties {
        providers = ordered(providers);
        blobStores = ordered(blobStores);
        caches = ordered(caches);
        parameterStores = ordered(parameterStores);
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

        public ProviderConfig toProviderConfig(String name) {
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
    /**
     * @param provider   the provider name; may be omitted when exactly one provider is configured
     * @param endpoint   {@code redis://host:port}, or {@code rediss://host:port} for TLS
     * @param namespace  key prefix ({@code namespace:key}); defaults to the bean name, empty for none
     * @param defaultTtl expiry for writes that do not give one; none when omitted
     * @param cluster    whether the server runs in cluster mode
     * @param failOpen   treat the cache as best effort when the server is unavailable (see {@code CacheConfig.failOpen})
     * @param auth       how to log in
     * @param properties provider-specific settings, such as {@code aws.elasticache.cache-name}
     */
    public record CacheProperties(String provider, URI endpoint, String namespace, Duration defaultTtl,
                                  @DefaultValue("false") boolean cluster, @DefaultValue("false") boolean failOpen,
                                  @DefaultValue CacheAuthProperties auth, Map<String, String> properties) {

        public CacheProperties {
            properties = ordered(properties);
        }

        CacheConfig toCacheConfig(String name) {
            if (endpoint == null) {
                throw new IllegalStateException("stratum.caches." + name + ".endpoint is required (redis:// or rediss://)");
            }
            CacheConfig.Builder config = CacheConfig.builder(endpoint)
                    .namespace(namespace == null ? name : namespace)
                    .cluster(cluster)
                    .failOpen(failOpen)
                    .auth(auth.toCacheAuth(name));
            if (defaultTtl != null) {
                config.defaultTtl(defaultTtl);
            }
            properties.forEach(config::property);
            return config.build();
        }
    }

    /**
     * @param type     {@code none}, {@code password} (AUTH token / access key, optionally with an ACL
     *                 username) or {@code iam} (cloud identity tokens: ElastiCache IAM, Entra ID, Memorystore IAM)
     * @param username ACL username; for {@code iam}, the ElastiCache user id or the Entra object id
     * @param password the password, for {@code password}
     */
    public record CacheAuthProperties(@DefaultValue("none") Type type, String username, String password) {

        public enum Type { NONE, PASSWORD, IAM }

        CacheAuth toCacheAuth(String cache) {
            return switch (type) {
                case NONE -> CacheAuth.none();
                case IAM -> username == null ? CacheAuth.iam() : CacheAuth.iam(username);
                case PASSWORD -> {
                    if (password == null) {
                        throw new IllegalStateException("stratum.caches." + cache + ".auth.password is required for password auth");
                    }
                    yield username == null ? CacheAuth.password(password) : CacheAuth.password(username, password);
                }
            };
        }
    }

    /**
     * The Spring {@code CacheManager} behind {@code @Cacheable}, {@code @CachePut} and {@code @CacheEvict}.
     * Each Spring cache becomes a Stratum cache whose namespace is {@code namespace-prefix + name}.
     *
     * @param provider        the provider name; may be omitted when exactly one provider is configured
     * @param endpoint        {@code redis://} or {@code rediss://} server; setting it enables the cache manager
     * @param auth            how to log in
     * @param cluster         whether the server runs in cluster mode
     * @param failOpen        treat the cache as best effort: an unavailable server makes cached methods run uncached
     * @param properties      provider-specific settings, such as {@code aws.elasticache.cache-name}
     * @param defaultTtl      expiry of entries; none when omitted
     * @param ttl             per-cache expiry overriding {@code default-ttl}, keyed by cache name
     * @param namespacePrefix prefix of every cache's namespace, for sharing a server between applications
     * @param cacheNames      caches to create up front; when set, unknown names are rejected
     * @param allowNullValues whether {@code null} results are cached
     * @param codec           {@code jdk} (Java serialization) or {@code json} (Jackson 3 on Boot 4, Jackson 2 on Boot 3)
     * @param trustedPackages packages whose classes may be deserialized; required for {@code json}, and
     *                        restricts {@code jdk} too when set ({@code java.*} is always allowed)
     */
    public record CacheManagerProperties(String provider, URI endpoint, @DefaultValue CacheAuthProperties auth,
                                         @DefaultValue("false") boolean cluster, @DefaultValue("false") boolean failOpen,
                                         Map<String, String> properties, Duration defaultTtl, Map<String, Duration> ttl,
                                         @DefaultValue("") String namespacePrefix, List<String> cacheNames,
                                         @DefaultValue("true") boolean allowNullValues,
                                         @DefaultValue("jdk") Codec codec, List<String> trustedPackages) {

        public enum Codec { JDK, JSON }

        public CacheManagerProperties {
            properties = ordered(properties);
            ttl = ordered(ttl);
            cacheNames = cacheNames == null ? List.of() : List.copyOf(cacheNames);
            trustedPackages = trustedPackages == null ? List.of() : List.copyOf(trustedPackages);
        }

        public CacheConfig toCacheConfig(String cacheName) {
            CacheConfig.Builder config = CacheConfig.builder(endpoint)
                    .namespace(namespacePrefix + cacheName)
                    .cluster(cluster)
                    .failOpen(failOpen)
                    .auth(auth.toCacheAuth("cache-manager"));
            Duration expiry = ttl.getOrDefault(cacheName, defaultTtl);
            if (expiry != null) {
                config.defaultTtl(expiry);
            }
            properties.forEach(config::property);
            return config.build();
        }
    }

    /**
     * @param provider   the provider name; may be omitted when exactly one provider is configured
     * @param kind       {@code parameters} or {@code secrets}
     * @param location   Key Vault URL or App Configuration endpoint on Azure, KV mount on Vault
     * @param prefix     name prefix of every value in the store
     * @param cacheTtl   how long reads are cached in this JVM; none when omitted
     * @param nativeNames use the backend's own names as they are (see {@code ParameterStoreConfig.nativeNames})
     * @param expandJson  for {@code spring.config.import}: also expose the fields of JSON object values as
     *                    properties ({@code db/creds} holding {@code {"password":...}} adds {@code db.creds.password})
     * @param reloadInterval for {@code spring.config.import}: poll for changes this often and update the imported
     *                    properties in place; no reloading when omitted
     * @param startupAttempts for {@code spring.config.import}: attempts when the backend is temporarily unavailable
     * @param properties provider-specific settings, such as {@code aws.ssm.secure}
     */
    public record ParameterStoreProperties(String provider, @DefaultValue("secrets") ParameterKind kind, String location,
                                           @DefaultValue("") String prefix, Duration cacheTtl,
                                           @DefaultValue("false") boolean nativeNames, @DefaultValue("true") boolean expandJson,
                                           Duration reloadInterval, @DefaultValue("3") int startupAttempts,
                                           Map<String, String> properties) {

        public ParameterStoreProperties {
            properties = ordered(properties);
        }

        public ParameterStoreConfig toStoreConfig() {
            ParameterStoreConfig.Builder config = ParameterStoreConfig.builder(kind).prefix(prefix).nativeNames(nativeNames);
            if (location != null) {
                config.location(location);
            }
            if (cacheTtl != null) {
                config.cacheTtl(cacheTtl);
            }
            properties.forEach(config::property);
            return config.build();
        }
    }

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
