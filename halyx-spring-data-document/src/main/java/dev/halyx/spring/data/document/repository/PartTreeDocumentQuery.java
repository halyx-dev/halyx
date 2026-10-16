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
import dev.halyx.nosql.Index;
import dev.halyx.nosql.IndexQuery;
import dev.halyx.nosql.Item;
import dev.halyx.nosql.Query;
import dev.halyx.nosql.QueryPage;
import dev.halyx.nosql.SortCondition;
import org.springframework.dao.IncorrectResultSizeDataAccessException;
import org.springframework.data.domain.KeysetScrollPosition;
import org.springframework.data.domain.Limit;
import org.springframework.data.domain.ScrollPosition;
import org.springframework.data.domain.Sort;
import org.springframework.data.domain.Window;
import org.springframework.data.repository.query.ParametersParameterAccessor;
import org.springframework.data.repository.query.QueryMethod;
import org.springframework.data.repository.query.RepositoryQuery;
import org.springframework.data.repository.query.parser.Part;
import org.springframework.data.repository.query.parser.PartTree;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.IntFunction;
import java.util.stream.Stream;

/**
 * A query derived from a repository method's name. The method must find items by the partition key, or by the
 * partition attribute of a declared secondary index, with at most one condition on the matching sort attribute
 * and ordering only by it: the shapes every document store answers without scanning the table. Anything else
 * fails when the repository is created, with a message saying what is supported.
 *
 * <pre>{@code
 * List<Order> findByCustomerId(String customerId);
 * List<Order> findByCustomerIdAndOrderIdBetweenOrderByOrderIdDesc(String customerId, String from, String to);
 * Window<Order> findByCustomerId(String customerId, ScrollPosition position, Limit limit);
 * Stream<Order> findByStatus(Status status);                     // a declared index on "status"
 * Optional<Order> findFirstByCustomerIdOrderByOrderIdDesc(String customerId);
 * boolean existsByStatus(Status status);
 * }</pre>
 */
final class PartTreeDocumentQuery<T extends Record> implements RepositoryQuery {

    /** The scroll position key that carries Halyx' page token. */
    static final String PAGE_TOKEN = "pageToken";

    private static final Set<Part.Type> SORT_CONDITIONS = Set.of(Part.Type.SIMPLE_PROPERTY, Part.Type.BETWEEN,
            Part.Type.STARTING_WITH, Part.Type.GREATER_THAN, Part.Type.GREATER_THAN_EQUAL, Part.Type.LESS_THAN,
            Part.Type.LESS_THAN_EQUAL, Part.Type.AFTER, Part.Type.BEFORE);

    enum Kind { LIST, STREAM, WINDOW, SINGLE, EXISTS }

    private final QueryMethod method;
    private final DocumentStore store;
    private final EntityModel<T> model;
    private final String description;
    /** Null for a query on the partition key. */
    private final Index index;
    private final String partitionAttribute;
    private final int partitionArgument;
    private final Part.Type sortType;
    private final String sortAttribute;
    private final int sortArgument;
    private final boolean descending;
    private final Integer maxResults;
    private final Kind kind;

    PartTreeDocumentQuery(QueryMethod method, DocumentStore store, EntityModel<T> model) {
        this(method, store, model, null);
    }

    /**
     * @param reactiveKind for a reactive repository, the kind its return type asks for: STREAM for a Flux, WINDOW
     *                     for a Mono of a Window, SINGLE for any other Mono; null to take it from the method
     */
    PartTreeDocumentQuery(QueryMethod method, DocumentStore store, EntityModel<T> model, Kind reactiveKind) {
        this.method = method;
        this.store = store;
        this.model = model;
        this.description = method.getName() + " on " + model.type().getSimpleName();
        PartTree tree;
        try {
            tree = new PartTree(method.getName(), model.type());
        } catch (RuntimeException e) {
            throw invalid(e.getMessage());
        }
        if (tree.isDelete()) {
            throw invalid("derived deletes aren't supported; find the entities and delete them");
        }
        if (tree.isCountProjection()) {
            throw invalid("counting would read every matching item; there is no count query");
        }
        if (tree.isDistinct()) {
            throw invalid("Distinct isn't supported");
        }
        List<Part> parts = parts(tree);

        Part keyPart = null;
        Index foundIndex = null;
        for (Part part : parts) {
            String name = PropertyPaths.segment(part);
            if (part.getType() != Part.Type.SIMPLE_PROPERTY) {
                continue;
            }
            if (name.equals(model.partitionComponent())) {
                keyPart = part;
                break;
            }
            Optional<Index> declared = store.table().indexes().values().stream()
                    .filter(i -> i.partitionAttribute().equals(name)).findFirst();
            if (declared.isPresent() && foundIndex == null) {
                keyPart = part;
                foundIndex = declared.get();
            }
        }
        if (keyPart == null) {
            throw invalid("it must find items by the partition key (" + model.partitionComponent()
                    + ") or by the partition attribute of a secondary index declared on the table "
                    + store.table().indexes().values().stream().map(Index::partitionAttribute).toList()
                    + "; anything else would scan the table");
        }
        if (PropertyPaths.segment(keyPart).equals(model.partitionComponent())) {
            foundIndex = null;
            if (!model.hasSortKey()) {
                throw invalid("the table has no sort key, so the partition key finds one item: use findById");
            }
        }
        this.index = foundIndex;
        this.partitionAttribute = PropertyPaths.segment(keyPart);
        this.sortAttribute = index == null ? model.sortComponent().orElse(null) : index.sortAttribute().orElse(null);

        int argument = 0;
        int keyArgument = -1;
        Part sortPart = null;
        int sortPartArgument = -1;
        for (Part part : parts) {
            if (part == keyPart) {
                keyArgument = argument;
            } else {
                String name = PropertyPaths.segment(part);
                if (sortPart != null || !name.equals(sortAttribute)) {
                    throw invalid("besides " + partitionAttribute + ", it may only have one condition on "
                            + (sortAttribute == null ? "the sort attribute, which " + where() + " doesn't have"
                            : sortAttribute) + "; " + name + " would need a scan");
                }
                if (!SORT_CONDITIONS.contains(part.getType())) {
                    throw invalid(part.getType() + " isn't supported on " + sortAttribute + ": use Is, Between, "
                            + "StartingWith, GreaterThan(Equal), LessThan(Equal), After or Before");
                }
                sortPart = part;
                sortPartArgument = argument;
            }
            argument += part.getNumberOfArguments();
        }
        this.partitionArgument = keyArgument;
        this.sortType = sortPart == null ? null : sortPart.getType();
        this.sortArgument = sortPartArgument;

        this.descending = descending(tree.getSort());
        this.maxResults = tree.getMaxResults();

        var parameters = method.getParameters();
        if (parameters.hasPageableParameter()) {
            throw invalid("Pageable needs offsets, which document stores don't have; use Window and ScrollPosition");
        }
        if (parameters.hasSortParameter()) {
            throw invalid("a Sort parameter isn't supported; order by " + (sortAttribute == null ? "the sort attribute"
                    : sortAttribute) + " in the method name (OrderBy...Desc)");
        }
        if (!method.getReturnedObjectType().equals(model.type()) && !tree.isExistsProjection()) {
            throw invalid("it must return " + model.type().getSimpleName() + "s; projections aren't supported");
        }
        if (tree.isExistsProjection()) {
            this.kind = Kind.EXISTS;
        } else if (reactiveKind != null) {
            if (reactiveKind == Kind.WINDOW && !parameters.hasScrollPositionParameter()) {
                throw invalid("a Window needs a ScrollPosition parameter");
            }
            this.kind = reactiveKind;
        } else if (method.isScrollQuery()) {
            if (!parameters.hasScrollPositionParameter()) {
                throw invalid("a Window needs a ScrollPosition parameter");
            }
            this.kind = Kind.WINDOW;
        } else if (method.isSliceQuery() || method.isPageQuery()) {
            throw invalid("Slice and Page need offsets, which document stores don't have; return a Window");
        } else if (method.isStreamQuery()) {
            this.kind = Kind.STREAM;
        } else if (method.isCollectionQuery()) {
            this.kind = Kind.LIST;
        } else {
            this.kind = Kind.SINGLE;
        }
        if (kind != Kind.WINDOW && parameters.hasScrollPositionParameter()) {
            throw invalid("a ScrollPosition parameter needs a Window return type");
        }
    }

    Kind kind() {
        return kind;
    }

    @Override
    public QueryMethod getQueryMethod() {
        return method;
    }

    @Override
    public Object execute(Object[] parameters) {
        ParametersParameterAccessor accessor = new ParametersParameterAccessor(method.getParameters(), parameters);
        Integer limit = limit(accessor.getLimit());
        return switch (kind) {
            case EXISTS -> items(accessor, 1, null).findAny().isPresent();
            case SINGLE -> {
                List<Item> found = items(accessor, 2, null).limit(2).toList();
                if (found.size() > 1 && maxResults == null) {
                    throw new IncorrectResultSizeDataAccessException(description + " found more than one item", 1);
                }
                yield found.isEmpty() ? null : model.entity(found.getFirst());
            }
            case LIST -> limited(items(accessor, pageSize(limit), null), limit).map(model::entity).toList();
            case STREAM -> limited(items(accessor, pageSize(limit), null), limit).map(model::entity);
            case WINDOW -> window(accessor, limit);
        };
    }

    private Window<T> window(ParametersParameterAccessor accessor, Integer limit) {
        String token = pageToken(accessor.getScrollPosition());
        QueryPage page = page(accessor, pageSize(limit), token);
        List<T> entities = page.items().stream().map(model::entity).toList();
        Optional<String> next = page.nextPageToken();
        int last = entities.size() - 1;
        IntFunction<ScrollPosition> positions = i -> {
            if (i != last || next.isEmpty()) {
                throw new UnsupportedOperationException("A Halyx window can only continue after its last item");
            }
            return ScrollPosition.forward(Map.of(PAGE_TOKEN, next.get()));
        };
        return Window.from(entities, positions, next.isPresent());
    }

    private String pageToken(ScrollPosition position) {
        if (position == null || position.isInitial()) {
            return null;
        }
        if (!(position instanceof KeysetScrollPosition keyset)) {
            throw new IllegalArgumentException(description + ": only keyset positions are supported, starting from "
                    + "ScrollPosition.keyset(); offsets need a scan");
        }
        if (keyset.scrollsBackward()) {
            throw new IllegalArgumentException(description + ": windows only scroll forward");
        }
        Object token = keyset.getKeys().get(PAGE_TOKEN);
        if (!(token instanceof String value)) {
            throw new IllegalArgumentException(description + ": not a position from a Halyx window: " + keyset.getKeys());
        }
        return value;
    }

    /** Every item, page by page as the stream is consumed. */
    private Stream<Item> items(ParametersParameterAccessor accessor, int pageSize, String token) {
        return Stream.iterate(page(accessor, pageSize, token), page -> page != null,
                        page -> page.nextPageToken().map(next -> page(accessor, pageSize, next)).orElse(null))
                .flatMap(page -> page.items().stream());
    }

    private QueryPage page(ParametersParameterAccessor accessor, int pageSize, String token) {
        SortCondition condition = sortType == null ? null : condition(accessor);
        if (index == null) {
            Query.Builder query = Query.builder(EntityModel.keyValue(
                    accessor.getBindableValue(partitionArgument), partitionAttribute)).limit(pageSize);
            if (condition != null) {
                query.where(condition);
            }
            if (descending) {
                query.descending();
            }
            if (token != null) {
                query.pageToken(token);
            }
            return store.query(query.build());
        }
        IndexQuery.Builder query = IndexQuery.builder(index.name())
                .equalTo(EntityModel.indexValue(accessor.getBindableValue(partitionArgument), partitionAttribute))
                .limit(pageSize);
        if (condition != null) {
            query.where(condition);
        }
        if (descending) {
            query.descending();
        }
        if (token != null) {
            query.pageToken(token);
        }
        return store.queryIndex(query.build());
    }

    private SortCondition condition(ParametersParameterAccessor accessor) {
        String value = EntityModel.keyValue(accessor.getBindableValue(sortArgument), sortAttribute);
        return switch (sortType) {
            case SIMPLE_PROPERTY -> SortCondition.equalTo(value);
            case BETWEEN -> SortCondition.between(value,
                    EntityModel.keyValue(accessor.getBindableValue(sortArgument + 1), sortAttribute));
            case STARTING_WITH -> SortCondition.beginsWith(value);
            case GREATER_THAN, AFTER -> SortCondition.greaterThan(value);
            case GREATER_THAN_EQUAL -> SortCondition.greaterThanOrEqual(value);
            case LESS_THAN, BEFORE -> SortCondition.lessThan(value);
            case LESS_THAN_EQUAL -> SortCondition.lessThanOrEqual(value);
            default -> throw new IllegalStateException("Unexpected " + sortType);
        };
    }

    /** The smaller of First/Top in the name and a Limit parameter, or null for all. */
    private Integer limit(Limit parameter) {
        Integer fromParameter = parameter != null && parameter.isLimited() ? parameter.max() : null;
        if (fromParameter != null && fromParameter < 1) {
            throw new IllegalArgumentException(description + ": the limit must be positive");
        }
        if (maxResults == null) {
            return fromParameter;
        }
        return fromParameter == null ? maxResults : Math.min(maxResults, fromParameter);
    }

    private static int pageSize(Integer limit) {
        return limit == null ? Query.DEFAULT_LIMIT : Math.min(limit, Query.MAX_LIMIT);
    }

    private static Stream<Item> limited(Stream<Item> items, Integer limit) {
        return limit == null ? items : items.limit(limit);
    }

    private boolean descending(Sort sort) {
        List<Sort.Order> orders = sort.toList();
        if (orders.isEmpty()) {
            return false;
        }
        if (orders.size() > 1 || sortAttribute == null || !orders.getFirst().getProperty().equals(sortAttribute)) {
            throw invalid("results come in " + (sortAttribute == null ? "no particular order from " + where()
                    : sortAttribute + " order") + "; it can only be ordered by " + (sortAttribute == null
                    ? "nothing" : sortAttribute + ", ascending or descending"));
        }
        if (orders.getFirst().isIgnoreCase()) {
            throw invalid("ordering ignoring case isn't supported");
        }
        return orders.getFirst().isDescending();
    }

    private List<Part> parts(PartTree tree) {
        List<PartTree.OrPart> or = new ArrayList<>();
        tree.forEach(or::add);
        if (or.size() != 1) {
            throw invalid("Or isn't supported: each query reads one partition or one index value");
        }
        List<Part> parts = new ArrayList<>();
        or.getFirst().forEach(parts::add);
        for (Part part : parts) {
            if (PropertyPaths.nested(part)) {
                throw invalid("conditions on nested properties (" + PropertyPaths.dotPath(part) + ") need a scan");
            }
            if (part.shouldIgnoreCase() != Part.IgnoreCaseType.NEVER) {
                throw invalid("IgnoreCase isn't supported: keys compare exactly");
            }
        }
        return parts;
    }

    private String where() {
        return index == null ? "the table" : "index " + index.name();
    }

    private IllegalStateException invalid(String reason) {
        return new IllegalStateException("Halyx can't derive a query for " + description + ": " + reason);
    }
}
