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
import org.springframework.data.repository.core.EntityInformation;
import org.springframework.data.repository.core.RepositoryInformation;
import org.springframework.data.repository.core.RepositoryMetadata;
import org.springframework.data.repository.core.support.RepositoryFactorySupport;
import org.springframework.data.repository.query.QueryLookupStrategy;
import org.springframework.data.repository.query.QueryMethod;
import org.springframework.data.repository.query.ValueExpressionDelegate;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;

/**
 * Creates repositories over document stores: a {@link SimpleDocumentRepository} for the CRUD methods, and a
 * {@link PartTreeDocumentQuery} for each query method. {@code stores} finds the document store of an entity.
 */
public class HalyxRepositoryFactory extends RepositoryFactorySupport {

    private final Function<Class<?>, DocumentStore> stores;
    private final Map<Class<?>, EntityModel<?>> models = new ConcurrentHashMap<>();

    /**
     * @param stores the document store of an entity type, such as the {@code DocumentStore} bean named by its
     *               {@code @DocumentTable}
     */
    public HalyxRepositoryFactory(Function<Class<?>, DocumentStore> stores) {
        this.stores = stores;
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
        return getTargetRepositoryViaReflection(information, store(model), model);
    }

    @Override
    protected Class<?> getRepositoryBaseClass(RepositoryMetadata metadata) {
        return SimpleDocumentRepository.class;
    }

    @Override
    protected Optional<QueryLookupStrategy> getQueryLookupStrategy(QueryLookupStrategy.Key key,
                                                                   ValueExpressionDelegate valueExpressionDelegate) {
        return Optional.of((method, metadata, projections, namedQueries) -> {
            EntityModel<?> model = model(metadata.getDomainType());
            return new PartTreeDocumentQuery<>(new QueryMethod(method, metadata, projections), store(model), model);
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
