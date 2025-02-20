package org.apache.flink.connector.clickhouse.internal.executor;

import org.apache.flink.api.common.functions.RuntimeContext;
import org.apache.flink.connector.clickhouse.config.ClickHouseConfigOptions.SinkUpdateStrategy;
import org.apache.flink.connector.clickhouse.internal.ClickHouseShardOutputFormat;
import org.apache.flink.connector.clickhouse.internal.connection.ClickHouseConnectionProvider;
import org.apache.flink.connector.clickhouse.internal.converter.ClickHouseRowConverter;
import org.apache.flink.connector.clickhouse.internal.converter.ClickHouseStatementWrapper;
import org.apache.flink.connector.clickhouse.internal.options.ClickHouseDmlOptions;
import org.apache.flink.table.data.RowData;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import ru.yandex.clickhouse.ClickHouseConnection;
import ru.yandex.clickhouse.ClickHousePreparedStatement;

import java.sql.SQLException;
import java.util.Arrays;
import java.util.function.Function;

import static org.apache.flink.connector.clickhouse.config.ClickHouseConfigOptions.SinkUpdateStrategy.DISCARD;
import static org.apache.flink.connector.clickhouse.config.ClickHouseConfigOptions.SinkUpdateStrategy.INSERT;
import static org.apache.flink.connector.clickhouse.config.ClickHouseConfigOptions.SinkUpdateStrategy.UPDATE;

/** ClickHouse's upsert executor. */
public class ClickHouseUpsertExecutor implements ClickHouseExecutor {

    private static final long serialVersionUID = 1L;

    private static final Logger LOG = LoggerFactory.getLogger(ClickHouseShardOutputFormat.class);

    private final String insertSql;

    private final String updateSql;

    private final String deleteSql;

    private final ClickHouseRowConverter insertConverter;

    private final ClickHouseRowConverter updateConverter;

    private final ClickHouseRowConverter deleteConverter;

    private final Function<RowData, RowData> updateExtractor;

    private final Function<RowData, RowData> deleteExtractor;

    private final int maxRetries;

    private final SinkUpdateStrategy updateStrategy;

    private final boolean ignoreDelete;

    private transient ClickHouseStatementWrapper insertStatement;

    private transient ClickHouseStatementWrapper updateStatement;

    private transient ClickHouseStatementWrapper deleteStatement;

    private transient ClickHouseConnectionProvider connectionProvider;

    public ClickHouseUpsertExecutor(
            String insertSql,
            String updateSql,
            String deleteSql,
            ClickHouseRowConverter insertConverter,
            ClickHouseRowConverter updateConverter,
            ClickHouseRowConverter deleteConverter,
            Function<RowData, RowData> updateExtractor,
            Function<RowData, RowData> deleteExtractor,
            ClickHouseDmlOptions options) {
        this.insertSql = insertSql;
        this.updateSql = updateSql;
        this.deleteSql = deleteSql;
        this.insertConverter = insertConverter;
        this.updateConverter = updateConverter;
        this.deleteConverter = deleteConverter;
        this.updateExtractor = updateExtractor;
        this.deleteExtractor = deleteExtractor;
        this.maxRetries = options.getMaxRetries();
        this.updateStrategy = options.getUpdateStrategy();
        this.ignoreDelete = options.isIgnoreDelete();
    }

    @Override
    public void prepareStatement(ClickHouseConnection connection) throws SQLException {
        this.insertStatement =
                new ClickHouseStatementWrapper(
                        (ClickHousePreparedStatement) connection.prepareStatement(this.insertSql));
        this.updateStatement =
                new ClickHouseStatementWrapper(
                        (ClickHousePreparedStatement) connection.prepareStatement(this.updateSql));
        this.deleteStatement =
                new ClickHouseStatementWrapper(
                        (ClickHousePreparedStatement) connection.prepareStatement(this.deleteSql));
    }

    @Override
    public void prepareStatement(ClickHouseConnectionProvider connectionProvider)
            throws SQLException {
        this.connectionProvider = connectionProvider;
        prepareStatement(connectionProvider.getOrCreateConnection());
    }

    @Override
    public void setRuntimeContext(RuntimeContext context) {}

    @Override
    public void addToBatch(RowData record) throws SQLException {
        switch (record.getRowKind()) {
            case INSERT:
                insertConverter.toExternal(record, insertStatement);
                insertStatement.addBatch();
                break;
            case UPDATE_AFTER:
                if (INSERT.equals(updateStrategy)) {
                    insertConverter.toExternal(record, insertStatement);
                    insertStatement.addBatch();
                } else if (UPDATE.equals(updateStrategy)) {
                    updateConverter.toExternal(updateExtractor.apply(record), updateStatement);
                    updateStatement.addBatch();
                } else if (DISCARD.equals(updateStrategy)) {
                    LOG.debug("Discard a record of type UPDATE_AFTER: {}", record);
                } else {
                    throw new RuntimeException("Unknown update strategy: " + updateStrategy);
                }
                break;
            case DELETE:
                if (!ignoreDelete) {
                    deleteConverter.toExternal(deleteExtractor.apply(record), deleteStatement);
                    deleteStatement.addBatch();
                }
                break;
            case UPDATE_BEFORE:
                break;
            default:
                throw new UnsupportedOperationException(
                        String.format(
                                "Unknown row kind, the supported row kinds is: INSERT, UPDATE_BEFORE, UPDATE_AFTER, DELETE, but get: %s.",
                                record.getRowKind()));
        }
    }

    @Override
    public void executeBatch() throws SQLException {
        for (ClickHouseStatementWrapper clickHouseStatement :
                Arrays.asList(insertStatement, updateStatement, deleteStatement)) {
            if (clickHouseStatement != null) {
                attemptExecuteBatch(clickHouseStatement, maxRetries);
            }
        }
    }

    @Override
    public void closeStatement() {
        for (ClickHouseStatementWrapper clickHouseStatement :
                Arrays.asList(insertStatement, updateStatement, deleteStatement)) {
            if (clickHouseStatement != null) {
                try {
                    clickHouseStatement.close();
                } catch (SQLException exception) {
                    LOG.warn("ClickHouse upsert statement could not be closed.", exception);
                }
            }
        }
    }

    @Override
    public String toString() {
        return "ClickHouseUpsertExecutor{"
                + "insertSql='"
                + insertSql
                + '\''
                + ", updateSql='"
                + updateSql
                + '\''
                + ", deleteSql='"
                + deleteSql
                + '\''
                + ", maxRetries="
                + maxRetries
                + ", updateStrategy="
                + updateStrategy
                + ", ignoreDelete="
                + ignoreDelete
                + ", connectionProvider="
                + connectionProvider
                + '}';
    }
}
