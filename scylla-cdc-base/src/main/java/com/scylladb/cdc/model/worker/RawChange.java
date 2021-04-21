package com.scylladb.cdc.model.worker;

import java.nio.ByteBuffer;

import com.scylladb.cdc.model.worker.cql.Cell;

/*
 * Represents a single CDC log row,
 * without any post-processing.
 */
public interface RawChange {
    enum OperationType {
        PRE_IMAGE((byte) 0),
        ROW_UPDATE((byte) 1),
        ROW_INSERT((byte) 2),
        ROW_DELETE((byte) 3),
        PARTITION_DELETE((byte) 4),
        ROW_RANGE_DELETE_INCLUSIVE_LEFT_BOUND((byte) 5),
        ROW_RANGE_DELETE_EXCLUSIVE_LEFT_BOUND((byte) 6),
        ROW_RANGE_DELETE_INCLUSIVE_RIGHT_BOUND((byte) 7),
        ROW_RANGE_DELETE_EXCLUSIVE_RIGHT_BOUND((byte) 8),
        POST_IMAGE((byte) 9);

        // Assumes that operationId are consecutive and start from 0.
        private static final OperationType[] enumValues = OperationType.values();

        byte operationId;
        OperationType(byte operationId) {
            this.operationId = operationId;
        }

        public static OperationType parse(byte value) {
            // TODO - validation
            return enumValues[value];
        }
    }

    ChangeId getId();

    default OperationType getOperationType() {
        Byte operation = getCell("cdc$operation").getByte();
        return OperationType.parse(operation);
    }

    default Long getTTL() {
        return getCell("cdc$ttl").getLong();
    }

    ChangeSchema getSchema();

    /*
     * Gets the value of column as Java Object.
     */
    default Object getAsObject(String columnName) {
        return getAsObject(getSchema().getColumnDefinition(columnName));
    }

    Object getAsObject(ChangeSchema.ColumnDefinition c);

    default Cell getCell(String columnName) {
        return getCell(getSchema().getColumnDefinition(columnName));
    }

    Cell getCell(ChangeSchema.ColumnDefinition c);

    default boolean isNull(String columnName) {
        return isNull(getSchema().getColumnDefinition(columnName));
    }

    boolean isNull(ChangeSchema.ColumnDefinition c);

    default ByteBuffer getUnsafeBytes(String columnName) {
        return getUnsafeBytes(getSchema().getColumnDefinition(columnName));
    }

    ByteBuffer getUnsafeBytes(ChangeSchema.ColumnDefinition c);

    default boolean getIsDeleted(String columnName) {
        String deletedColumnName = "cdc$deleted_" + columnName;
        Boolean value = getCell(deletedColumnName).getBoolean();
        return value != null && value;
    }
}
