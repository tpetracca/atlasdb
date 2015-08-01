package com.palantir.atlasdb.keyvalue.partition;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.google.common.base.Preconditions;
import com.google.common.base.Supplier;
import com.google.common.collect.HashMultimap;
import com.google.common.collect.ListMultimap;
import com.google.common.collect.Maps;
import com.google.common.collect.Multimap;
import com.google.common.collect.Multimaps;
import com.google.common.collect.Sets;
import com.google.common.primitives.UnsignedBytes;
import com.palantir.atlasdb.keyvalue.api.Cell;
import com.palantir.atlasdb.keyvalue.api.InsufficientConsistencyException;
import com.palantir.atlasdb.keyvalue.api.KeyValueService;
import com.palantir.atlasdb.keyvalue.api.RangeRequest;
import com.palantir.atlasdb.keyvalue.api.RangeRequest.Builder;
import com.palantir.atlasdb.keyvalue.api.Value;
import com.palantir.atlasdb.keyvalue.impl.InMemoryKeyValueService;
import com.palantir.atlasdb.keyvalue.partition.api.TableAwarePartitionMapApi;
import com.palantir.atlasdb.keyvalue.partition.util.ConsistentRingRangeComparator;


public final class BasicPartitionMap implements TableAwarePartitionMapApi {

    final Map<String, byte[]> tableMetadata;
    final CycleMap<byte[], KeyValueService> ring;
    final QuorumParameters quorumParameters;

    private BasicPartitionMap(QuorumParameters quorumParameters, Collection<KeyValueService> services, byte[][] points) {
        Preconditions.checkArgument(services.size() > 0);
        Preconditions.checkArgument(services.size() == points.length);
        this.quorumParameters = quorumParameters;
        tableMetadata = Maps.newHashMap();
        ring = CycleMap.wrap(Maps.<byte[], byte[], KeyValueService>newTreeMap(UnsignedBytes.lexicographicalComparator()));
        int i = 0;
        for (KeyValueService kvs : services) {
            ring.put(points[i++], kvs);
        }
    }

    public static BasicPartitionMap create(QuorumParameters quorumParameter, Collection<KeyValueService> services, byte[][] points) {
        return new BasicPartitionMap(quorumParameter, services, points);
    }

    public static BasicPartitionMap create(QuorumParameters quorumParameters, int numOfServices) {
        Preconditions.checkArgument(numOfServices < 255);
        KeyValueService[] services = new KeyValueService[numOfServices];
        byte[][] points = new byte[numOfServices][];
        for (int i=0; i<numOfServices; ++i) {
            services[i] = new InMemoryKeyValueService(false);
        }
        for (int i=0; i<numOfServices; ++i) {
            points[i] = new byte[] {(byte) (i + 1)};
        }
        return new BasicPartitionMap(quorumParameters, Arrays.asList(services), points);
    }

    private Set<KeyValueService> getServicesHavingRow(byte[] key) {
        Set<KeyValueService> result = Sets.newHashSet();
        byte[] point = ring.nextKey(key);
        for (int i=0; i<quorumParameters.getReplicationFactor(); ++i) {
            result.add(ring.get(point));
            point = ring.nextKey(point);
        }
        return result;
    }

    static boolean inRange(byte[] position, RangeRequest rangeRequest) {
        Preconditions.checkNotNull(rangeRequest);
        Preconditions.checkNotNull(position);
        int cmpStart = UnsignedBytes.lexicographicalComparator().compare(position, rangeRequest.getStartInclusive());
        int cmpEnd = UnsignedBytes.lexicographicalComparator().compare(position, rangeRequest.getEndExclusive());
        if (rangeRequest.isReverse()) {
            return (rangeRequest.getStartInclusive().length == 0 || cmpStart <= 0) && cmpEnd > 0;
        } else {
            return cmpStart >= 0 && (rangeRequest.getEndExclusive().length == 0 || cmpEnd < 0);
        }
    }

    @Override
    public Set<String> getAllTableNames() {
        return tableMetadata.keySet();
    }

    @Override
    public void addTable(String tableName, int maxValueSize) throws InsufficientConsistencyException {
        if (tableMetadata.containsKey(tableName)) {
            return;
        }
        // Should work for HashMap
        storeTableMetadata(tableName, null);
        for (KeyValueService kvs : getAllServices()) {
            kvs.createTable(tableName, maxValueSize);
        }
    }

    @Override
    public void dropTable(String tableName) throws InsufficientConsistencyException {
        for (KeyValueService kvs : getAllServices()) {
            kvs.dropTable(tableName);
        }
        tableMetadata.remove(tableName);
    }

    @Override
    public void truncateTable(String tableName) throws InsufficientConsistencyException {
        for (KeyValueService kvs : getAllServices()) {
            kvs.truncateTable(tableName);
        }
    }

    @Override
    public void truncateTables(Set<String> tableNamess) throws InsufficientConsistencyException {
        for (KeyValueService kvs : getAllServices()) {
            kvs.truncateTables(tableNamess);
        }
    }

    @Override
    public void storeTableMetadata(String tableName, byte[] metadata) {
        tableMetadata.put(tableName, metadata);
    }

    @Override
    public byte[] getTableMetadata(String tableName) {
        return tableMetadata.get(tableName);
    }

    @Override
    public Map<String, byte[]> getTablesMetadata() {
        return Maps.newHashMap(tableMetadata);
    }

    @Override
    public void tearDown() {
        for (KeyValueService kvs : getAllServices()) {
            kvs.teardown();
        }
    }

    private Set<KeyValueService> getAllServices() {
        final Set<KeyValueService> result = Sets.newHashSet();
        for (Map.Entry<byte[], KeyValueService> e : ring.entrySet()) {
            result.add(e.getValue());
        }
        return result;
    }

    @Override
    public void close() {
        Set<KeyValueService> services = Sets.newHashSet(ring.values());
        for (KeyValueService keyValueService : services) {
            keyValueService.close();
        }
    }

    @Override
    public Multimap<ConsistentRingRangeRequest, KeyValueService> getServicesForRangeRead(String tableName,
                                                                                         RangeRequest range) {
        ListMultimap<ConsistentRingRangeRequest, KeyValueService> result = Multimaps.newListMultimap(
                Maps.<ConsistentRingRangeRequest, ConsistentRingRangeRequest, Collection<KeyValueService>>newTreeMap(ConsistentRingRangeComparator.instance()),
                new Supplier<List<KeyValueService>>() {
                    @Override
                    public List<KeyValueService> get() {
                        return new ArrayList<KeyValueService>();
                    }
                });
        CycleMap<byte[], KeyValueService> rangeRing = ring;
        if (range.isReverse()) {
            rangeRing = rangeRing.descendingMap();
        }

        byte[] key = new byte[0];
        if (range.getStartInclusive().length > 0) {
            key = range.getStartInclusive();
        }

        while (key != null && inRange(key, range)) {
            Set<KeyValueService> services = Sets.newHashSet();
            Builder builder = range.isReverse() ? RangeRequest.reverseBuilder() : RangeRequest.builder();
            builder = builder.startRowInclusive(key);
            if (rangeRing.higherKey(key) != null) {
                builder = builder.endRowExclusive(rangeRing.nextKey(key));
            } else {
                // unbounded
            }
            ConsistentRingRangeRequest crrr = ConsistentRingRangeRequest.of(builder.build());
            byte[] kvsKey = rangeRing.nextKey(key);
            for (int i = 0; i < quorumParameters.getReplicationFactor(); ++i) {
                services.add(rangeRing.get(kvsKey));
                kvsKey = rangeRing.nextKey(kvsKey);
            }
            result.putAll(crrr, services);
            key = rangeRing.higherKey(key);
        }

        return result;
    }

    @Override
    public Map<KeyValueService, ? extends Iterable<byte[]>> getServicesForRowsRead(String tableName,
                                                                         Iterable<byte[]> rows) {
        Map<KeyValueService, Set<byte[]>> result = Maps.newHashMap();
        for (byte[] row : rows) {
            Set<KeyValueService> services = getServicesHavingRow(row);
            for (KeyValueService kvs : services) {
                if (!result.containsKey(kvs)) {
                    result.put(kvs, Sets.<byte[]>newTreeSet(UnsignedBytes.lexicographicalComparator()));
                }
                assert(!result.get(kvs).contains(row));
                result.get(kvs).add(row);
            }
        }
        return result;
    }

    @Override
    public Map<KeyValueService, Map<Cell, Long>> getServicesForCellsRead(String tableName,
                                                                         Map<Cell, Long> timestampByCell) {
        Map<KeyValueService, Map<Cell, Long>> result = Maps.newHashMap();
        for (Map.Entry<Cell, Long> e : timestampByCell.entrySet()) {
            Set<KeyValueService> services = getServicesHavingRow(e.getKey().getRowName());
            for (KeyValueService kvs : services) {
                if (!result.containsKey(kvs)) {
                    result.put(kvs, Maps.<Cell, Long>newHashMap());
                }
                assert(result.get(kvs).containsKey(e.getKey()) == false);
                result.get(kvs).put(e.getKey(), e.getValue());
            }
        }
        return result;
    }

    @Override
    public Map<KeyValueService, Set<Cell>> getServicesForCellsRead(String tableName,
                                                                   Set<Cell> cells,
                                                                   long timestamp) {
        Map<KeyValueService, Set<Cell>> result = Maps.newHashMap();
        for (Cell cell : cells) {
            Set<KeyValueService> services = getServicesHavingRow(cell.getRowName());
            for (KeyValueService kvs : services) {
                if (!result.containsKey(kvs)) {
                    result.put(kvs, Sets.<Cell>newHashSet());
                }
                assert(result.get(kvs).contains(cell) == false);
                result.get(kvs).add(cell);
            }
        }
        return result;
    }

    @Override
    public Map<KeyValueService, Map<Cell, byte[]>> getServicesForCellsWrite(String tableName,
                                                                            Map<Cell, byte[]> values) {
        Map<KeyValueService, Map<Cell, byte[]>> result = Maps.newHashMap();
        for (Map.Entry<Cell, byte[]> e : values.entrySet()) {
            Set<KeyValueService> services = getServicesHavingRow(e.getKey().getRowName());
            for (KeyValueService kvs : services) {
                if (!result.containsKey(kvs)) {
                    result.put(kvs, Maps.<Cell, byte[]>newHashMap());
                }
                assert(!result.get(kvs).containsKey(e.getKey()));
                result.get(kvs).put(e.getKey(), e.getValue());
            }
        }
        return result;
    }

    @Override
    public Map<KeyValueService, Set<Cell>> getServicesForCellsWrite(String tableName,
                                                                    Set<Cell> cells) {
        Map<KeyValueService, Set<Cell>> result = Maps.newHashMap();
        for (Cell cell : cells) {
            Set<KeyValueService> services = getServicesHavingRow(cell.getRowName());
            for (KeyValueService kvs : services) {
                if (!result.containsKey(kvs)) {
                    result.put(kvs, Sets.<Cell>newHashSet());
                }
                assert(!result.get(kvs).contains(cell));
                result.get(kvs).add(cell);
            }
        }
        return result;
    }

    @Override
    public Map<KeyValueService, Multimap<Cell, Value>> getServicesForTimestampsWrite(String tableName,
                                                                                     Multimap<Cell, Value> cellValues) {
        Map<KeyValueService, Multimap<Cell, Value>> result = Maps.newHashMap();
        for (Map.Entry<Cell, Value> e : cellValues.entries()) {
            Set<KeyValueService> services = getServicesHavingRow(e.getKey().getRowName());
            for (KeyValueService kvs: services) {
                if (!result.containsKey(kvs)) {
                    result.put(kvs, HashMultimap.<Cell, Value>create());
                }
                assert(!result.get(kvs).containsEntry(e.getKey(), e.getValue()));
                result.get(kvs).put(e.getKey(), e.getValue());
            }
        }
        return result;
    }

    @Override
    public Map<KeyValueService, Multimap<Cell, Long>> getServicesForDelete(String tableName,
                                                                           Multimap<Cell, Long> keys) {
        Map<KeyValueService, Multimap<Cell, Long>> result = Maps.newHashMap();
        for (Map.Entry<Cell, Long> e : keys.entries()) {
            Set<KeyValueService> services = getServicesHavingRow(e.getKey().getRowName());
            for (KeyValueService kvs : services) {
               if (!result.containsKey(kvs)) {
                   result.put(kvs, HashMultimap.<Cell, Long>create());
               }
               assert(!result.get(kvs).containsEntry(e.getKey(), e.getValue()));
               result.get(kvs).put(e.getKey(), e.getValue());
            }
        }
        return result;
    }
}