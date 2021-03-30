package com.scylladb.cdc.replicator.operations.update;

import com.datastax.driver.core.ColumnMetadata;
import com.datastax.driver.core.ConsistencyLevel;
import com.datastax.driver.core.DataType;
import com.datastax.driver.core.Session;
import com.datastax.driver.core.Statement;
import com.datastax.driver.core.TableMetadata;
import com.datastax.driver.core.querybuilder.Assignment;
import com.datastax.driver.core.querybuilder.ListSetIdxTimeUUIDAssignment;
import com.datastax.driver.core.querybuilder.QueryBuilder;
import com.datastax.driver.core.querybuilder.UdtSetFieldAssignment;
import com.datastax.driver.core.querybuilder.Update;
import com.scylladb.cdc.model.worker.RawChange;
import com.scylladb.cdc.model.worker.cql.Field;
import com.scylladb.cdc.replicator.driver3.Driver3FromLibraryTranslator;
import com.scylladb.cdc.replicator.operations.ExecutingStatementHandler;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import java.util.stream.Collectors;

import static com.datastax.driver.core.DataType.Name.UDT;
import static com.datastax.driver.core.querybuilder.QueryBuilder.*;

public class UnpreparedUpdateOperationHandler extends ExecutingStatementHandler {
    private final TableMetadata table;
    private final Driver3FromLibraryTranslator driver3FromLibraryTranslator;

    public UnpreparedUpdateOperationHandler(Session session, TableMetadata t, Driver3FromLibraryTranslator d3t) {
        super(session);
        table = t;
        driver3FromLibraryTranslator = d3t;
    }

    @Override
    public Statement getStatement(RawChange change, ConsistencyLevel cl) {
        Update builder = QueryBuilder.update(table);
        Set<ColumnMetadata> primaryColumns = new HashSet<>(table.getPrimaryKey());
        table.getColumns().stream().forEach(c -> {
            if (primaryColumns.contains(c)) {
                builder.where(eq(c.getName(), driver3FromLibraryTranslator.translate(change.getCell(c.getName()))));
            } else {
                Assignment op = null;
                boolean isNonFrozenCollection = c.getType().isCollection() && !c.getType().isFrozen();
                boolean isNonFrozenUDT = c.getType().getName() == UDT && !c.getType().isFrozen();
                String deletedElementsColumnName = "cdc$deleted_elements_" + c.getName();
                if (isNonFrozenCollection && !change.getIsDeleted(c.getName())) {
                    if (change.getAsObject(deletedElementsColumnName) != null) {
                        if (c.getType().getName() == DataType.Name.SET) {
                            op = removeAll(c.getName(), (Set) driver3FromLibraryTranslator.translate(change.getCell(deletedElementsColumnName)));
                        } else if (c.getType().getName() == DataType.Name.MAP) {
                            op = removeAll(c.getName(), (Set) driver3FromLibraryTranslator.translate(change.getCell(deletedElementsColumnName)));
                        } else if (c.getType().getName() == DataType.Name.LIST) {
                            Set<UUID> cSet = (Set<UUID>) driver3FromLibraryTranslator.translate(change.getCell(deletedElementsColumnName));
                            for (UUID key : cSet) {
                                builder.with(new ListSetIdxTimeUUIDAssignment(c.getName(), key, null));
                            }
                            return;
                        } else {
                            throw new IllegalStateException();
                        }
                    } else {
                        if (c.getType().getName() == DataType.Name.SET) {
                            op = addAll(c.getName(), (Set) driver3FromLibraryTranslator.translate(change.getCell(c.getName())));
                        } else if (c.getType().getName() == DataType.Name.MAP) {
                            op = putAll(c.getName(), (Map) driver3FromLibraryTranslator.translate(change.getCell(c.getName())));
                        } else if (c.getType().getName() == DataType.Name.LIST) {
                            Map<UUID, Object> cMap = (Map<UUID, Object>) driver3FromLibraryTranslator.translate(change.getCell(c.getName()));
                            for (Map.Entry<UUID, Object> e : cMap.entrySet()) {
                                builder.with(new ListSetIdxTimeUUIDAssignment(c.getName(), e.getKey(), e.getValue()));
                            }
                            return;
                        } else {
                            throw new IllegalStateException();
                        }
                    }
                } else if (isNonFrozenUDT && !change.getIsDeleted(c.getName())) {
                    Map<String, Field> libraryUdt = change.getCell(c.getName()).getUDT();
                    Set<Field> deletedElements = change.getCell(deletedElementsColumnName).getSet();
                    if (deletedElements == null) {
                        deletedElements = Collections.emptySet();
                    }
                    Set<Short> deletedIdx = deletedElements.stream().map(Field::getShort).collect(Collectors.toCollection(HashSet<Short>::new));
                    Set<Map.Entry<String, Field>> udtFields = libraryUdt.entrySet();
                    Iterator<Map.Entry<String, Field>> udtFieldsIterator = udtFields.iterator();
                    for (int idx = 0; udtFieldsIterator.hasNext(); idx++) {
                        Map.Entry<String, Field> field = udtFieldsIterator.next();

                        Object fieldValue = driver3FromLibraryTranslator.translate(field.getValue().getAsObject(),
                                field.getValue().getDataType());
                        if (fieldValue == null && !deletedIdx.contains((short)idx)) {
                            continue;
                        }

                        builder.with(new UdtSetFieldAssignment(c.getName(), field.getKey(), fieldValue));
                    }
                    return;
                }
                if (op == null) {
                    if (c.getType().getName() == DataType.Name.LIST && !c.getType().isFrozen()) {
                        Map<UUID, Object> cMap = (Map<UUID, Object>) driver3FromLibraryTranslator.translate(change.getCell(c.getName()));
                        if (cMap == null) {
                            op = set(c.getName(), null);
                        } else {
                            TreeMap<UUID, Object> sorted = new TreeMap<>();
                            for (Map.Entry<UUID, Object> e : cMap.entrySet()) {
                                sorted.put(e.getKey(), e.getValue());
                            }
                            List<Object> list = new ArrayList<>();
                            for (Map.Entry<UUID, Object> e : sorted.entrySet()) {
                                list.add(e.getValue());
                            }
                            op = set(c.getName(), list);
                        }
                    } else {
                        op = set(c.getName(), driver3FromLibraryTranslator.translate(change.getCell(c.getName())));
                    }
                }
                builder.with(op);
            }
        });
        Long ttl = change.getTTL();
        if (ttl != null) {
            builder.using(timestamp(change.getId().getChangeTime().getTimestamp())).and(ttl((int) ((long) ttl)));
        } else {
            builder.using(timestamp(change.getId().getChangeTime().getTimestamp()));
        }
        builder.setConsistencyLevel(cl);
        return builder;
    }

}
