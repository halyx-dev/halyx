package org.kamranzafar.cirrus.spring.autoconfigure;

import org.kamranzafar.cirrus.Cirrus;
import org.kamranzafar.cirrus.cache.Cache;
import org.kamranzafar.cirrus.cache.CacheConfig;
import org.kamranzafar.cirrus.messaging.Publisher;
import org.kamranzafar.cirrus.messaging.Subscription;
import org.kamranzafar.cirrus.parameters.ParameterStore;
import org.kamranzafar.cirrus.parameters.ParameterStoreConfig;
import org.kamranzafar.cirrus.sql.Database;
import org.kamranzafar.cirrus.sql.SqlAuth;
import org.kamranzafar.cirrus.storage.BlobStore;

import javax.sql.DataSource;

/**
 * Factory behind the beans declared under {@code cirrus.blob-stores}, {@code cirrus.caches},
 * {@code cirrus.parameter-stores} and {@code cirrus.databases}.
 */
public final class CirrusBlobStores {

    private final Cirrus cirrus;

    public CirrusBlobStores(Cirrus cirrus) {
        this.cirrus = cirrus;
    }

    public BlobStore blobStore(String provider, String bucket) {
        return cirrus.provider(provider).blobStore(bucket);
    }

    public Cache<String> cache(String provider, CacheConfig config) {
        return cirrus.provider(provider).cache(config);
    }

    public ParameterStore parameterStore(String provider, ParameterStoreConfig config) {
        return cirrus.provider(provider).parameterStore(config);
    }

    public Publisher publisher(String provider, String name, CirrusProperties.TopicProperties topic) {
        return cirrus.provider(provider).publisher(topic.toTopicConfig(name, null));
    }

    public Publisher publisherWithClaimCheck(String provider, String name, CirrusProperties.TopicProperties topic,
                                             BlobStore claims) {
        return cirrus.provider(provider).publisher(topic.toTopicConfig(name, topic.claimCheck().toClaimCheck(claims)));
    }

    public Subscription subscription(String provider, String name, CirrusProperties.SubscriptionProperties subscription) {
        return cirrus.provider(provider).subscription(subscription.toSubscriptionConfig(name, null));
    }

    public Subscription subscriptionWithClaimCheck(String provider, String name,
                                                   CirrusProperties.SubscriptionProperties subscription, BlobStore claims) {
        return cirrus.provider(provider).subscription(
                subscription.toSubscriptionConfig(name, subscription.claimCheck().toClaimCheck(claims)));
    }

    /** A pooled database with a fixed password or cloud IAM login. The pool closes with Cirrus. */
    public DataSource dataSource(String provider, String name, CirrusProperties.DatabaseProperties database) {
        return database(provider, name, database, null).dataSource();
    }

    /** A pooled database whose password is read from {@code secrets} for every new connection. */
    public DataSource dataSourceWithSecret(String provider, String name, CirrusProperties.DatabaseProperties database,
                                           ParameterStore secrets) {
        return database(provider, name, database, secrets).dataSource();
    }

    private Database database(String provider, String name, CirrusProperties.DatabaseProperties database,
                              ParameterStore secrets) {
        String prefix = "cirrus.databases." + name + ".auth";
        var auth = database.auth();
        SqlAuth resolved = switch (auth.type()) {
            case IAM -> auth.username() == null ? SqlAuth.iam() : SqlAuth.iam(auth.username());
            case PASSWORD -> {
                if (auth.username() == null) {
                    throw new IllegalStateException(prefix + ".username is required for password auth");
                }
                if (secrets != null) {
                    var secret = auth.secret();
                    if (secret.name() == null) {
                        throw new IllegalStateException(prefix + ".secret.name is required");
                    }
                    yield SqlAuth.password(auth.username(), () -> secret.field() == null
                            ? secrets.require(secret.name())
                            : secrets.require(secret.name(), secret.field()));
                }
                if (auth.password() == null) {
                    throw new IllegalStateException(prefix + ".password (or " + prefix + ".secret) is required");
                }
                yield SqlAuth.password(auth.username(), auth.password());
            }
        };
        return cirrus.provider(provider).database(database.toSqlConfig(name, resolved));
    }
}
