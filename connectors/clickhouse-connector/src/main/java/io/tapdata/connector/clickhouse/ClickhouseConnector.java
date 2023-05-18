package io.tapdata.connector.clickhouse;

import io.tapdata.common.CommonDbConnector;
import io.tapdata.common.CommonSqlMaker;
import io.tapdata.common.SqlExecuteCommandFunction;
import io.tapdata.connector.clickhouse.bean.ClickhouseColumn;
import io.tapdata.connector.clickhouse.config.ClickhouseConfig;
import io.tapdata.connector.clickhouse.ddl.sqlmaker.ClickhouseDDLSqlGenerator;
import io.tapdata.connector.clickhouse.ddl.sqlmaker.ClickhouseDDLSqlMaker;
import io.tapdata.connector.clickhouse.dml.ClickhouseBatchWriter;
import io.tapdata.connector.clickhouse.dml.TapTableWriter;
import io.tapdata.entity.codec.TapCodecsRegistry;
import io.tapdata.entity.event.ddl.index.TapCreateIndexEvent;
import io.tapdata.entity.event.ddl.table.*;
import io.tapdata.entity.event.dml.TapRecordEvent;
import io.tapdata.entity.logger.TapLogger;
import io.tapdata.entity.schema.TapField;
import io.tapdata.entity.schema.TapIndex;
import io.tapdata.entity.schema.TapIndexField;
import io.tapdata.entity.schema.TapTable;
import io.tapdata.entity.schema.value.*;
import io.tapdata.entity.simplify.TapSimplify;
import io.tapdata.entity.simplify.pretty.BiClassHandlers;
import io.tapdata.entity.utils.DataMap;
import io.tapdata.kit.DbKit;
import io.tapdata.kit.EmptyKit;
import io.tapdata.pdk.apis.annotations.TapConnectorClass;
import io.tapdata.pdk.apis.context.TapConnectionContext;
import io.tapdata.pdk.apis.context.TapConnectorContext;
import io.tapdata.pdk.apis.entity.*;
import io.tapdata.pdk.apis.functions.ConnectorFunctions;
import io.tapdata.pdk.apis.functions.connection.TableInfo;
import org.apache.commons.codec.binary.Base64;

import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import java.util.stream.Collectors;

@TapConnectorClass("spec_clickhouse.json")
public class ClickhouseConnector extends CommonDbConnector {

    public static final String TAG = ClickhouseConnector.class.getSimpleName();

    private ClickhouseConfig clickhouseConfig;
    private ClickhouseJdbcContext clickhouseJdbcContext;
    private String clickhouseVersion;

    private final ClickhouseBatchWriter clickhouseWriter = new ClickhouseBatchWriter(TAG);

    @Override
    public void onStart(TapConnectionContext connectionContext) throws Throwable {
        initConnection(connectionContext);
        ddlSqlGenerator = new ClickhouseDDLSqlGenerator();
        fieldDDLHandlers = new BiClassHandlers<>();
        fieldDDLHandlers.register(TapNewFieldEvent.class, this::newField);
        fieldDDLHandlers.register(TapAlterFieldAttributesEvent.class, this::alterFieldAttr);
        fieldDDLHandlers.register(TapAlterFieldNameEvent.class, this::alterFieldName);
        fieldDDLHandlers.register(TapDropFieldEvent.class, this::dropField);
        if (connectionContext instanceof TapConnectorContext) {
            TapConnectorContext tapConnectorContext = (TapConnectorContext) connectionContext;
            Optional.ofNullable(tapConnectorContext.getConnectorCapabilities()).ifPresent(connectorCapabilities -> {
                Optional.ofNullable(connectorCapabilities.getCapabilityAlternative(ConnectionOptions.DML_INSERT_POLICY)).ifPresent(clickhouseWriter::setInsertPolicy);
                Optional.ofNullable(connectorCapabilities.getCapabilityAlternative(ConnectionOptions.DML_UPDATE_POLICY)).ifPresent(clickhouseWriter::setUpdatePolicy);
            });
        }

    }

    private void initConnection(TapConnectionContext connectionContext) throws Throwable {
        clickhouseConfig = new ClickhouseConfig().load(connectionContext.getConnectionConfig());
        clickhouseJdbcContext = new ClickhouseJdbcContext(clickhouseConfig);
        commonDbConfig = clickhouseConfig;
        jdbcContext = clickhouseJdbcContext;
        clickhouseVersion = clickhouseJdbcContext.queryVersion();
        commonSqlMaker = new CommonSqlMaker('`');
    }

    @Override
    protected void singleThreadDiscoverSchema(List<DataMap> subList, Consumer<List<TapTable>> consumer) throws SQLException {
        List<TapTable> tapTableList = TapSimplify.list();
        List<String> subTableNames = subList.stream().map(v -> v.getString("tableName")).collect(Collectors.toList());
        List<DataMap> columnList = jdbcContext.queryAllColumns(subTableNames);
        subList.forEach(subTable -> {
            //1、table name/comment
            String table = subTable.getString("tableName");
            TapTable tapTable = table(table);
            tapTable.setComment(subTable.getString("tableComment"));
            //2、table columns info
            AtomicInteger keyPos = new AtomicInteger(0);
            AtomicInteger primaryKeyPos = new AtomicInteger(0);
//            AtomicInteger partitionKeyPos = new AtomicInteger(0);
            List<TapIndexField> sortingIndexFields = new ArrayList<>();
            List<TapIndexField> primaryIndexFields = new ArrayList<>();
            columnList.stream().filter(col -> table.equals(col.getString("tableName")))
                    .forEach(col -> {
                        ClickhouseColumn column = new ClickhouseColumn(col);
                        TapField tapField = column.getTapField();
                        tapField.setPos(keyPos.incrementAndGet());
                        if (column.isPrimary()) {
                            tapField.setPrimaryKey(true);
                            tapField.setPrimaryKeyPos(primaryKeyPos.incrementAndGet());
                            primaryIndexFields.add(new TapIndexField().name(tapField.getName()).fieldAsc(true));
                        }
                        if (column.isSorting()) {
                            sortingIndexFields.add(new TapIndexField().name(tapField.getName()).fieldAsc(true));
                        }
//                        if (column.isPartition()) {
//                            tapField.setPartitionKey(column.isPartition());
//                            tapField.setPartitionKeyPos(partitionKeyPos.incrementAndGet());
//                        }
                        tapTable.add(tapField);
                    });
            if (EmptyKit.isNotEmpty(primaryIndexFields)) {
                TapIndex tapIndex = new TapIndex().primary(true).unique(true);
                tapIndex.setIndexFields(primaryIndexFields);
                tapTable.add(tapIndex);
            }
            if (EmptyKit.isNotEmpty(sortingIndexFields) && (primaryIndexFields.size() != sortingIndexFields.size())) {
                TapIndex tapIndex = new TapIndex().primary(false).unique(false);
                tapIndex.setIndexFields(sortingIndexFields);
                tapTable.add(tapIndex);
            }
            tapTableList.add(tapTable);
        });
        syncSchemaSubmit(tapTableList, consumer);
    }

    @Override
    public void onStop(TapConnectionContext connectionContext) {
        EmptyKit.closeQuietly(clickhouseJdbcContext);
        EmptyKit.closeQuietly(clickhouseWriter);
    }

    @Override
    public void registerCapabilities(ConnectorFunctions connectorFunctions, TapCodecsRegistry codecRegistry) {

        codecRegistry.registerFromTapValue(TapRawValue.class, "String", tapRawValue -> {
            if (tapRawValue != null && tapRawValue.getValue() != null) return toJson(tapRawValue.getValue());
            return "null";
        });
        codecRegistry.registerFromTapValue(TapMapValue.class, "String", tapMapValue -> {
            if (tapMapValue != null && tapMapValue.getValue() != null) return toJson(tapMapValue.getValue());
            return "null";
        });
        codecRegistry.registerFromTapValue(TapArrayValue.class, "String", tapValue -> {
            if (tapValue != null && tapValue.getValue() != null) return toJson(tapValue.getValue());
            return "null";
        });
        codecRegistry.registerFromTapValue(TapBooleanValue.class, "UInt8", tapValue -> {
            if (tapValue.getValue()) return 1;
            else return 0;
        });

        codecRegistry.registerFromTapValue(TapTimeValue.class, tapTimeValue -> formatTapDateTime(tapTimeValue.getValue(), "HH:mm:ss.SS"));
        codecRegistry.registerFromTapValue(TapBinaryValue.class, "String", tapValue -> {
            if (tapValue != null && tapValue.getValue() != null)
                return new String(Base64.encodeBase64(tapValue.getValue()));
            return null;
        });

        //TapTimeValue, TapDateTimeValue and TapDateValue's value is DateTime, need convert into Date object.
//        codecRegistry.registerFromTapValue(TapTimeValue.class, tapTimeValue -> tapTimeValue.getValue().toTime());
        codecRegistry.registerFromTapValue(TapDateTimeValue.class, tapDateTimeValue -> {
            DateTime datetime = tapDateTimeValue.getValue();
//            datetime.setTimeZone(TimeZone.getTimeZone(this.connectionTimezone));
            return datetime.toTimestamp();
        });
        codecRegistry.registerFromTapValue(TapDateValue.class, tapDateValue -> {
            DateTime datetime = tapDateValue.getValue();
//            datetime.setTimeZone(TimeZone.getTimeZone(this.connectionTimezone));
            return datetime.toSqlDate();
        });

        connectorFunctions.supportErrorHandleFunction(this::errorHandle);
        //target
        connectorFunctions.supportCreateTable(this::createTable);
        connectorFunctions.supportDropTable(this::dropTable);
        connectorFunctions.supportClearTable(this::clearTable);
//        connectorFunctions.supportCreateIndex(this::createIndex);
        connectorFunctions.supportWriteRecord(this::writeRecord);


        //source
        connectorFunctions.supportBatchCount(this::batchCount);
        connectorFunctions.supportBatchRead(this::batchReadWithoutOffset);
//        connectorFunctions.supportStreamRead(this::streamRead);
//        connectorFunctions.supportTimestampToStreamOffset(this::timestampToStreamOffset);
        //query
        connectorFunctions.supportQueryByAdvanceFilter(this::queryByAdvanceFilter);
        connectorFunctions.supportQueryByFilter(this::queryByFilter);

        // ddl
        connectorFunctions.supportNewFieldFunction(this::fieldDDLHandler);
        connectorFunctions.supportAlterFieldNameFunction(this::fieldDDLHandler);
        connectorFunctions.supportAlterFieldAttributesFunction(this::fieldDDLHandler);
        connectorFunctions.supportDropFieldFunction(this::fieldDDLHandler);

        connectorFunctions.supportExecuteCommandFunction((a, b, c) -> SqlExecuteCommandFunction.executeCommand(a, b, () -> clickhouseJdbcContext.getConnection(), this::isAlive, c));
        connectorFunctions.supportRunRawCommandFunction(this::runRawCommand);
        connectorFunctions.supportGetTableInfoFunction(this::getTableInfo);

    }

    private void createTable(TapConnectorContext tapConnectorContext, TapCreateTableEvent tapCreateTableEvent) {
        TapTable tapTable = tapCreateTableEvent.getTable();
        StringBuilder sql = new StringBuilder("CREATE TABLE IF NOT EXISTS ");
        sql.append(TapTableWriter.sqlQuota(".", clickhouseConfig.getDatabase(), tapTable.getId()));
        sql.append("(").append(ClickhouseDDLSqlMaker.buildColumnDefinition(tapTable, true));
        sql.setLength(sql.length() - 1);

        // primary key
        Collection<String> primaryKeys = tapTable.primaryKeys(true);
        if (EmptyKit.isNotEmpty(primaryKeys)) {
            sql.append(") ENGINE = ReplacingMergeTree");
            sql.append(" PRIMARY KEY (").append(TapTableWriter.sqlQuota(",", primaryKeys)).append(")");
        } else {
            sql.append(") ENGINE = MergeTree");
        }

        // sorting key
        if (EmptyKit.isNotEmpty(primaryKeys)) {
            sql.append(" ORDER BY (").append(TapTableWriter.sqlQuota(",", primaryKeys)).append(")");
        } else {
            sql.append(" ORDER BY tuple()");
        }

        try {
            List<String> sqls = TapSimplify.list();
            sqls.add(sql.toString());
            TapLogger.info("table :", "table -> {}", tapTable.getId());
            clickhouseJdbcContext.batchExecute(sqls);
        } catch (Throwable e) {
            throw new RuntimeException("Create Table " + tapTable.getId() + " Failed! " + e.getMessage(), e);
        }
    }

    private void writeRecord(TapConnectorContext tapConnectorContext, List<TapRecordEvent> tapRecordEvents, TapTable tapTable, Consumer<WriteListResult<TapRecordEvent>> consumer) throws Throwable {
        WriteListResult<TapRecordEvent> writeListResult = new WriteListResult<>();
        TapTableWriter instance = clickhouseWriter.partition(clickhouseJdbcContext, this::isAlive);
        for (TapRecordEvent event : tapRecordEvents) {
            if (!isAlive()) {
                throw new InterruptedException("node not alive");
            }
            instance.addBath(tapTable, event, writeListResult);
        }
        instance.summit(writeListResult);
        consumer.accept(writeListResult);
    }

    //需要改写成ck的创建索引方式
    protected void createIndex(TapConnectorContext connectorContext, TapTable tapTable, TapCreateIndexEvent createIndexEvent) {
        try {
            List<String> sqls = TapSimplify.list();
            if (EmptyKit.isNotEmpty(createIndexEvent.getIndexList())) {
                createIndexEvent.getIndexList().stream().filter(i -> !i.isPrimary()).forEach(i ->
                        sqls.add("CREATE " + (i.isUnique() ? "UNIQUE " : " ") + "INDEX " +
                                (EmptyKit.isNotNull(i.getName()) ? "IF NOT EXISTS " + TapTableWriter.sqlQuota(i.getName()) : "") + " ON " + TapTableWriter.sqlQuota(".", clickhouseConfig.getDatabase(), tapTable.getId()) + "(" +
                                i.getIndexFields().stream().map(f -> TapTableWriter.sqlQuota(f.getName()) + " " + (f.getFieldAsc() ? "ASC" : "DESC"))
                                        .collect(Collectors.joining(",")) + ')'));
            }
            clickhouseJdbcContext.batchExecute(sqls);
        } catch (Throwable e) {
            e.printStackTrace();
            throw new RuntimeException("Create Indexes for " + tapTable.getId() + " Failed! " + e.getMessage());
        }

    }

    private void queryByAdvanceFilter(TapConnectorContext connectorContext, TapAdvanceFilter filter, TapTable table, Consumer<FilterResults> consumer) throws Throwable {
        StringBuilder builder = new StringBuilder("SELECT ");
        Projection projection = filter.getProjection();
        if (EmptyKit.isNull(projection) || (EmptyKit.isEmpty(projection.getIncludeFields()) && EmptyKit.isEmpty(projection.getExcludeFields()))) {
            builder.append("*");
        } else {
            builder.append("\"");
            if (EmptyKit.isNotEmpty(filter.getProjection().getIncludeFields())) {
                builder.append(String.join("\",\"", filter.getProjection().getIncludeFields()));
            } else {
                builder.append(table.getNameFieldMap().keySet().stream()
                        .filter(tapField -> !filter.getProjection().getExcludeFields().contains(tapField)).collect(Collectors.joining("\",\"")));
            }
            builder.append("\"");
        }
        builder.append(" FROM ").append(TapTableWriter.sqlQuota(".", clickhouseConfig.getDatabase(), table.getId())).append(" ").append(new CommonSqlMaker().buildSqlByAdvanceFilter(filter));
        clickhouseJdbcContext.query(builder.toString(), resultSet -> {
            FilterResults filterResults = new FilterResults();
            while (resultSet != null && resultSet.next()) {
                filterResults.add(DbKit.getRowFromResultSet(resultSet, DbKit.getColumnsFromResultSet(resultSet)));
                if (filterResults.getResults().size() == BATCH_ADVANCE_READ_LIMIT) {
                    consumer.accept(filterResults);
                    filterResults = new FilterResults();
                }
            }
            if (EmptyKit.isNotEmpty(filterResults.getResults())) {
                filterResults.getResults().stream().forEach(l -> l.entrySet().forEach(v -> {
                    if (v.getValue() instanceof String) {
                        v.setValue(((String) v.getValue()).trim());
                    }
                }));
                consumer.accept(filterResults);
            }
        });
    }

    @Override
    public ConnectionOptions connectionTest(TapConnectionContext connectionContext, Consumer<TestItem> consumer) throws Throwable {
        ConnectionOptions connectionOptions = ConnectionOptions.create();
        clickhouseConfig = new ClickhouseConfig().load(connectionContext.getConnectionConfig());
        try (
                ClickhouseTest clickhouseTest = new ClickhouseTest(clickhouseConfig, consumer)
        ) {
            clickhouseTest.testOneByOne();
            return connectionOptions;
        }
    }

    private TableInfo getTableInfo(TapConnectionContext tapConnectorContext, String tableName) throws Throwable {
        DataMap dataMap = clickhouseJdbcContext.getTableInfo(tableName);
        TableInfo tableInfo = TableInfo.create();
        tableInfo.setNumOfRows(Long.valueOf(dataMap.getString("NUM_ROWS")));
        tableInfo.setStorageSize(Long.valueOf(dataMap.getString("AVG_ROW_LEN")));
        return tableInfo;
    }

}
