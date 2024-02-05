/*
 * Copyright 2021 DataCanvas
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

package io.dingodb.store.proxy.meta;

import com.google.common.cache.CacheBuilder;
import com.google.common.cache.CacheLoader;
import com.google.common.cache.LoadingCache;
import io.dingodb.codec.CodecService;
import io.dingodb.codec.KeyValueCodec;
import io.dingodb.common.CommonId;
import io.dingodb.common.concurrent.Executors;
import io.dingodb.common.partition.RangeDistribution;
import io.dingodb.common.util.ByteArrayUtils.ComparableByteArray;
import io.dingodb.common.util.Optional;
import io.dingodb.common.util.Parameters;
import io.dingodb.meta.entity.Table;
import io.dingodb.sdk.service.MetaService;
import io.dingodb.sdk.service.Services;
import io.dingodb.sdk.service.entity.common.Location;
import io.dingodb.sdk.service.entity.common.RegionDefinition;
import io.dingodb.sdk.service.entity.meta.DingoCommonId;
import io.dingodb.sdk.service.entity.meta.EntityType;
import io.dingodb.sdk.service.entity.meta.GetIndexRangeRequest;
import io.dingodb.sdk.service.entity.meta.GetSchemaByNameRequest;
import io.dingodb.sdk.service.entity.meta.GetSchemaByNameResponse;
import io.dingodb.sdk.service.entity.meta.GetSchemasRequest;
import io.dingodb.sdk.service.entity.meta.GetTableRangeRequest;
import io.dingodb.sdk.service.entity.meta.GetTableRequest;
import io.dingodb.sdk.service.entity.meta.GetTablesRequest;
import io.dingodb.sdk.service.entity.meta.MetaEvent;
import io.dingodb.sdk.service.entity.meta.MetaEventIndex;
import io.dingodb.sdk.service.entity.meta.MetaEventRegion;
import io.dingodb.sdk.service.entity.meta.MetaEventSchema;
import io.dingodb.sdk.service.entity.meta.MetaEventTable;
import io.dingodb.sdk.service.entity.meta.MetaEventType;
import io.dingodb.sdk.service.entity.meta.Schema;
import io.dingodb.sdk.service.entity.meta.TableDefinitionWithId;
import io.dingodb.sdk.service.entity.meta.WatchRequest;
import io.dingodb.sdk.service.entity.meta.WatchRequest.RequestUnionNest.CreateRequest;
import io.dingodb.sdk.service.entity.meta.WatchRequest.RequestUnionNest.ProgressRequest;
import io.dingodb.sdk.service.entity.meta.WatchResponse;
import io.dingodb.store.proxy.service.TsoService;
import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;
import org.checkerframework.checker.nullness.qual.NonNull;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.NavigableMap;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;
import java.util.stream.Collectors;

import static io.dingodb.common.CommonId.CommonType.INDEX;
import static io.dingodb.common.CommonId.CommonType.TABLE;
import static io.dingodb.sdk.service.entity.meta.MetaEventType.META_EVENT_INDEX_DELETE;
import static io.dingodb.sdk.service.entity.meta.MetaEventType.META_EVENT_REGION_CREATE;
import static io.dingodb.sdk.service.entity.meta.MetaEventType.META_EVENT_REGION_DELETE;
import static io.dingodb.sdk.service.entity.meta.MetaEventType.META_EVENT_REGION_UPDATE;
import static io.dingodb.sdk.service.entity.meta.MetaEventType.META_EVENT_SCHEMA_CREATE;
import static io.dingodb.sdk.service.entity.meta.MetaEventType.META_EVENT_SCHEMA_DELETE;
import static io.dingodb.sdk.service.entity.meta.MetaEventType.META_EVENT_SCHEMA_UPDATE;
import static io.dingodb.sdk.service.entity.meta.MetaEventType.META_EVENT_TABLE_CREATE;
import static io.dingodb.sdk.service.entity.meta.MetaEventType.META_EVENT_TABLE_DELETE;
import static io.dingodb.sdk.service.entity.meta.MetaEventType.META_EVENT_TABLE_UPDATE;
import static io.dingodb.store.proxy.mapper.Mapper.MAPPER;
import static java.lang.Math.max;

@Slf4j
public class MetaCache {

    private final Set<Location> coordinators;
    private final MetaService metaService;
    private final TsoService tsoService;

    private final LoadingCache<String, Optional<Map<String, Table>>> cache;
    private final LoadingCache<CommonId, Table> tableIdCache;
    private Map<String, io.dingodb.store.proxy.meta.MetaService> metaServices;

    private final LoadingCache<CommonId, NavigableMap<ComparableByteArray, RangeDistribution>> distributionCache;

    public MetaCache(Set<Location> coordinators) {
        this.coordinators = coordinators;
        this.metaService = Services.metaService(coordinators);
        this.tsoService = TsoService.INSTANCE.isAvailable() ? TsoService.INSTANCE : new TsoService(coordinators);
        this.tableIdCache = buildTableIdCache();
        this.distributionCache = buildDistributionCache();
        this.cache = buildTableNamesCache();
        Executors.execute("watch-meta", () -> {
            while (true) {
                try {
                    watch();
                } catch (Exception e) {
                    log.error("Watch meta error, restart watch.", e);
                }
            }
        });
    }

    private long tso() {
        return tsoService.tso();
    }

    public void clear() {
        tableIdCache.invalidateAll();
        cache.invalidateAll();
        metaServices = null;
    }

    private void watch() {
        WatchResponse response = metaService.watch(
            tso(),
            WatchRequest.builder().requestUnion(CreateRequest.builder().eventTypes(eventTypes()).build()).build()
        );
        clear();
        long watchId = response.getWatchId();
        long revision = -1;
        while (true) {
            response = metaService.watch(
                tso(),
                WatchRequest.builder().requestUnion(ProgressRequest.builder().watchId(watchId).build()).build()
            );
            if (revision > 0 && revision < response.getCompactRevision()) {
                log.info(
                    "Watch id {} out, revision {}, compact revision {}, restart watch.",
                    watchId, revision, response.getCompactRevision()
                );
                return;
            }
            if (Parameters.cleanNull(response.getEvents(), Collections.EMPTY_LIST).isEmpty()) {
                continue;
            }
            for (MetaEvent event : response.getEvents()) {
                log.info("Receive meta event: {}", event);
                switch (event.getEventType()) {
                    case META_EVENT_NONE:
                        break;
                    case META_EVENT_SCHEMA_CREATE:
                    case META_EVENT_SCHEMA_UPDATE:
                    case META_EVENT_SCHEMA_DELETE: {
                        MetaEventSchema schemaEvent = (MetaEventSchema) event.getEvent();
                        invalidSchema(schemaEvent.getName().toUpperCase());
                        invalidMetaServices();
                        revision = max(revision, schemaEvent.getRevision());
                        break;
                    }
                    case META_EVENT_TABLE_CREATE:
                    case META_EVENT_TABLE_UPDATE:
                    case META_EVENT_TABLE_DELETE: {
                        MetaEventTable tableEvent = (MetaEventTable) event.getEvent();
                        String schemaName = getMetaService(tableEvent.getSchemaId()).name;
                        invalidSchema(schemaName);
                        invalidTable(tableEvent.getSchemaId(), tableEvent.getId());
                        revision = max(revision, tableEvent.getDefinition().getRevision());
                        break;
                    }
                    case META_EVENT_INDEX_DELETE: {
                        MetaEventIndex indexDeleteEvent = (MetaEventIndex) event.getEvent();
                        invalidTable(indexDeleteEvent.getSchemaId(), indexDeleteEvent.getId());
                        revision = max(revision, indexDeleteEvent.getDefinition().getRevision());
                        break;
                    }
                    case META_EVENT_REGION_CREATE:
                    case META_EVENT_REGION_UPDATE:
                    case META_EVENT_REGION_DELETE: {
                        invalidDistribution((MetaEventRegion) event.getEvent());
                        revision = max(revision, ((MetaEventRegion) event.getEvent()).getDefinition().getRevision());
                        break;
                    }
                    default:
                        throw new IllegalStateException("Unexpected value: " + event.getEventType());
                }
            }
        }
    }

    @NonNull
    private static List<MetaEventType> eventTypes() {
        return Arrays.asList(
            META_EVENT_SCHEMA_CREATE,
            META_EVENT_SCHEMA_UPDATE,
            META_EVENT_SCHEMA_DELETE,
            META_EVENT_TABLE_CREATE,
            META_EVENT_TABLE_UPDATE,
            META_EVENT_TABLE_DELETE,
            META_EVENT_INDEX_DELETE,
            META_EVENT_REGION_CREATE,
            META_EVENT_REGION_UPDATE,
            META_EVENT_REGION_DELETE
        );
    }

    private LoadingCache<String, Optional<Map<String, Table>>> buildTableNamesCache() {
        return CacheBuilder.newBuilder()
            .expireAfterAccess(60, TimeUnit.MINUTES).expireAfterWrite(60, TimeUnit.MINUTES)
            .build(new CacheLoader<String, Optional<Map<String, Table>>>() {
                @Override
                public Optional<Map<String, Table>> load(String schemaName) throws Exception {
                    return Optional.ofNullable(metaService.getSchemaByName(
                        tso(), GetSchemaByNameRequest.builder().schemaName(schemaName).build()
                    )).map(GetSchemaByNameResponse::getSchema).map(MetaCache.this::loadTables);
                }
            });
    }

    private Map<String, Table> loadTables(Schema schema) {
        if (schema.getTableIds() == null || schema.getTableIds().isEmpty()) {
            return Collections.emptyMap();
        }
        return schema.getTableIds().stream()
            .map(MAPPER::idFrom)
            .map(this::getTable)
            .collect(Collectors.toMap(Table::getName, Function.identity()));
    }

    private LoadingCache<CommonId, Table> buildTableIdCache() {
        return CacheBuilder.newBuilder()
            .expireAfterAccess(60, TimeUnit.MINUTES).expireAfterWrite(60, TimeUnit.MINUTES)
            .build(new CacheLoader<CommonId, Table>() {
                @Override
                public Table load(CommonId tableId) throws Exception {
                    return loadTable(tableId);
                }
            });
    }

    private LoadingCache<CommonId, NavigableMap<ComparableByteArray, RangeDistribution>> buildDistributionCache() {
        return CacheBuilder.newBuilder()
            .expireAfterAccess(10, TimeUnit.MINUTES).expireAfterWrite(10, TimeUnit.MINUTES)
            .build(new CacheLoader<CommonId, NavigableMap<ComparableByteArray, RangeDistribution>>() {
                @Override
                public NavigableMap<ComparableByteArray, RangeDistribution> load(CommonId key) throws Exception {
                    return loadDistribution(key);
                }
            });
    }

    private synchronized Table loadTable(CommonId tableId) {
        TableDefinitionWithId tableWithId = metaService.getTable(
            tso(), GetTableRequest.builder().tableId(MAPPER.idTo(tableId)).build()
        ).getTableDefinitionWithId();
        Table table = MAPPER.tableFrom(tableWithId, getIndexes(tableWithId, tableWithId.getTableId()));
        table.indexes.forEach($ -> tableIdCache.put($.getTableId(), $));
        return table;
    }

    private List<TableDefinitionWithId> getIndexes(TableDefinitionWithId tableWithId, DingoCommonId tableId) {
        return metaService.getTables(tso(), GetTablesRequest.builder().tableId(tableId).build())
            .getTableDefinitionWithIds().stream()
            .filter($ -> !$.getTableDefinition().getName().equalsIgnoreCase(tableWithId.getTableDefinition().getName()))
            .peek($ -> {
                String name1 = $.getTableDefinition().getName();
                String[] split = name1.split("\\.");
                if (split.length > 1) {
                    name1 = split[split.length - 1];
                }
                $.getTableDefinition().setName(name1);
            }).collect(Collectors.toList());
    }

    @SneakyThrows
    private NavigableMap<ComparableByteArray, RangeDistribution> loadDistribution(CommonId tableId) {
        List<io.dingodb.sdk.service.entity.meta.RangeDistribution> ranges;
        Table table = tableIdCache.get(tableId);
        KeyValueCodec codec = CodecService.getDefault().createKeyValueCodec(table.tupleType(), table.keyMapping());
        boolean isOriginalKey = table.getPartitionStrategy().equalsIgnoreCase("HASH");
        if (tableId.type == TABLE) {
            ranges = metaService.getTableRange(
                tso(), GetTableRangeRequest.builder().tableId(MAPPER.idTo(tableId)).build()
            ).getTableRange().getRangeDistribution();
        } else {
            ranges = metaService.getIndexRange(
                tso(), GetIndexRangeRequest.builder().indexId(MAPPER.idTo(tableId)).build()
            ).getIndexRange().getRangeDistribution();
        }
        NavigableMap<ComparableByteArray, RangeDistribution> result = new TreeMap<>();
        for (io.dingodb.sdk.service.entity.meta.RangeDistribution range : ranges) {
            RangeDistribution distribution = mapping(range, codec, isOriginalKey);
            result.put(new ComparableByteArray(distribution.getStartKey(), 1), distribution);
        }
        return result;
    }

    private RangeDistribution mapping(
        io.dingodb.sdk.service.entity.meta.RangeDistribution rangeDistribution,
        KeyValueCodec codec,
        boolean isOriginalKey
    ) {
        byte[] startKey = rangeDistribution.getRange().getStartKey();
        byte[] endKey = rangeDistribution.getRange().getEndKey();
        return RangeDistribution.builder()
            .id(MAPPER.idFrom(rangeDistribution.getId()))
            .startKey(startKey)
            .endKey(endKey)
            .start(codec.decodeKeyPrefix(isOriginalKey ? Arrays.copyOf(startKey, startKey.length) : startKey))
            .end(codec.decodeKeyPrefix(isOriginalKey ? Arrays.copyOf(endKey, endKey.length) : endKey))
            .build();
    }

    public void invalidTable(long schema, long table) {
        log.info("Invalid table {}.{}", schema, table);
        tableIdCache.invalidate(new CommonId(TABLE, schema, table));
        tableIdCache.invalidate(new CommonId(INDEX, schema, table));
    }

    public void invalidDistribution(MetaEventRegion metaEventRegion) {
        RegionDefinition definition = metaEventRegion.getDefinition();
        log.info("Invalid table distribution {}", definition);
        distributionCache.invalidate(new CommonId(TABLE, definition.getSchemaId(), definition.getTableId()));
        distributionCache.invalidate(new CommonId(INDEX, definition.getSchemaId(), definition.getTableId()));
    }

    public void invalidMetaServices() {
        log.info("Invalid meta services");
        metaServices = null;
    }

    public void invalidSchema(String schema) {
        log.info("Invalid schema {}", schema);
        cache.invalidate(schema);
    }

    @SneakyThrows
    public Table getTable(String schema, String table) {
        return cache.get(schema.toUpperCase()).map($ -> $.get(table.toUpperCase())).orNull();
    }

    @SneakyThrows
    public Table getTable(CommonId tableId) {
        return tableIdCache.get(tableId);
    }

    public io.dingodb.store.proxy.meta.MetaService getMetaService(long schemaId) {
        return getMetaServices().values().stream().filter($ -> $.id.getEntityId() == schemaId).findAny().orElse(null);
    }

    @SneakyThrows
    public Set<Table> getTables(String schema) {
        return new HashSet<>(cache.get(schema.toUpperCase()).orElseGet(Collections::emptyMap).values());
    }

    public Map<String, io.dingodb.store.proxy.meta.MetaService> getMetaServices() {
        if (metaServices == null) {
            metaServices = metaService.getSchemas(
                    tso(), GetSchemasRequest.builder().schemaId(io.dingodb.store.proxy.meta.MetaService.ROOT.id).build()
                ).getSchemas().stream()
                .filter($ -> $.getId() != null && $.getId().getEntityId() != 0)
                .peek($ -> $.getId().setEntityType(EntityType.ENTITY_TYPE_SCHEMA))
                .map(schema -> new io.dingodb.store.proxy.meta.MetaService(
                    schema.getId(), schema.getName().toUpperCase(), metaService, this
                )).collect(Collectors.toMap(io.dingodb.store.proxy.meta.MetaService::name, Function.identity()));
        }
        return metaServices;
    }

    @SneakyThrows
    public NavigableMap<ComparableByteArray, RangeDistribution> getRangeDistribution(CommonId id) {
        return distributionCache.get(id);
    }

}
