package org.kamranzafar.stratum.spring.autoconfigure;

import org.kamranzafar.stratum.cache.Cache;
import org.kamranzafar.stratum.parameters.ParameterStore;
import org.kamranzafar.stratum.storage.BlobStore;
import org.springframework.boot.autoconfigure.condition.AnyNestedCondition;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;

/** Matches when the context has at least one {@link BlobStore}, {@link Cache} or {@link ParameterStore} bean. */
public class StratumResourcesCondition extends AnyNestedCondition {

    public StratumResourcesCondition() {
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
}
