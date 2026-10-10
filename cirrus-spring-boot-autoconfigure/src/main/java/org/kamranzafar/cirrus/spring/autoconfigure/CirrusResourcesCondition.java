package org.kamranzafar.cirrus.spring.autoconfigure;

import org.kamranzafar.cirrus.cache.Cache;
import org.kamranzafar.cirrus.messaging.Subscription;
import org.kamranzafar.cirrus.nosql.DocumentStore;
import org.kamranzafar.cirrus.parameters.ParameterStore;
import org.kamranzafar.cirrus.storage.BlobStore;
import org.springframework.boot.autoconfigure.condition.AnyNestedCondition;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;

/** Matches when the context has at least one {@link BlobStore}, {@link Cache} or {@link ParameterStore} bean. */
public class CirrusResourcesCondition extends AnyNestedCondition {

    public CirrusResourcesCondition() {
        super(ConfigurationPhase.REGISTER_BEAN);
    }

    @ConditionalOnBean(BlobStore.class)
    static class HasBlobStore {
    }

    @ConditionalOnBean(Cache.class)
    static class HasCache {
    }

    @ConditionalOnBean(ParameterStore.class)
    static class HasParameterStore {
    }

    @ConditionalOnBean(Subscription.class)
    static class HasSubscription {
    }

    @ConditionalOnBean(DocumentStore.class)
    static class HasDocumentStore {
    }
}
