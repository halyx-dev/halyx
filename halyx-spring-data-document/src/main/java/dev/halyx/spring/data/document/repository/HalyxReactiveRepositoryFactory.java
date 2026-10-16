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
package dev.halyx.spring.data.document.repository;

import dev.halyx.nosql.DocumentStore;
import dev.halyx.reactor.ReactiveHalyx;
import org.springframework.data.repository.core.EntityInformation;
import org.springframework.data.repository.core.RepositoryInformation;
import org.springframework.data.repository.core.RepositoryMetadata;
import org.springframework.data.repository.core.support.ReactiveRepositoryFactorySupport;
import org.springframework.data.repository.query.QueryLookupStrategy;
import org.springframework.data.repository.query.ValueExpressionDelegate;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;

/**
 * Creates reactive repositories over document stores: a {@link SimpleReactiveDocumentRepository} for the CRUD
 * methods, and a {@link ReactivePartTreeDocumentQuery} for each query method, run on {@code reactive}'s scheduler.
 */
public class HalyxReactiveRepositoryFactory extends ReactiveRepositoryFactorySupport {

    private final Function<Class<?>, DocumentStore> stores;
    private final ReactiveHalyx reactive;
    private final Map<Class<?>, EntityModel<?>> models = new ConcurrentHashMap<>();

    /**
     * @param stores   the document store of an entity type, as for {@link HalyxRepositoryFactory}
     * @param reactive where the repositories' calls run
     */
    public HalyxReactiveRepositoryFactory(Function<Class<?>, DocumentStore> stores, ReactiveHalyx reactive) {
        this.stores = stores;
        this.reactive = reactive;
    }

    @Override
    @SuppressWarnings({"unchecked", "rawtypes"})
    public <T, ID> EntityInformation<T, ID> getEntityInformation(Class<T> domainClass) {
        return new DocumentEntityInformation(model(domainClass));
    }

    @Override
    @SuppressWarnings({"unchecked", "rawtypes"})
    protected Object getTargetRepository(RepositoryInformation information) {
        EntityModel<?> model = model(information.getDomainType());
        return new SimpleReactiveDocumentRepository(new SimpleDocumentRepository(store(model), model), reactive);
    }

    @Override
    protected Class<?> getRepositoryBaseClass(RepositoryMetadata metadata) {
        return SimpleReactiveDocumentRepository.class;
    }

    @Override
    protected Optional<QueryLookupStrategy> getQueryLookupStrategy(QueryLookupStrategy.Key key,
                                                                   ValueExpressionDelegate valueExpressionDelegate) {
        return Optional.of((method, metadata, projections, namedQueries) -> {
            EntityModel<?> model = model(metadata.getDomainType());
            return new ReactivePartTreeDocumentQuery<>(
                    new ReactivePartTreeDocumentQuery.ReactiveQueryMethod(method, metadata, projections),
                    store(model), model, reactive);
        });
    }

    private EntityModel<?> model(Class<?> domainType) {
        return models.computeIfAbsent(domainType, EntityModel::of);
    }

    private DocumentStore store(EntityModel<?> model) {
        DocumentStore store = stores.apply(model.type());
        model.checkAgainst(store.table(), model.storeName().orElse(store.table().name()));
        return store;
    }
}
