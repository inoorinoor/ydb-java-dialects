package tech.ydb.keycloak.testsuite.parameters;

import org.jboss.logging.Logger;
import tech.ydb.keycloak.testsuite.Config;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;

/**
 * Parameter class that connects to a pre-started (remote) YDB instance instead of spinning up
 * a container via YdbHelperFactory.
 *
 * <p>Use the {@code remote-ydb} Maven profile to activate this parameter class:
 * <pre>mvn test -P remote-ydb</pre>
 *
 * <p>The YDB instance must be started separately, for example via Docker Compose:
 * <pre>docker compose -f docker/docker-compose.ydb-test.yml up -d</pre>
 *
 * <p>The JDBC URL defaults to {@value #DEFAULT_JDBC_URL} (matching the port exposed by
 * {@code docker-compose.ydb-test.yml}). Override with the system property
 * {@value #JDBC_URL_PROPERTY}.
 *
 * <p>Tests that require this parameter class must be annotated with
 * {@code @RequireParameter(RemoteYdb.class)}.
 */
public class RemoteYdb extends AbstractYdbParameters {

    private final Logger LOG = Logger.getLogger(RemoteYdb.class);

    public static final String JDBC_URL_PROPERTY = "keycloak.ydb.remote.jdbc.url";
                                                //"jdbc:ydb:grpc://ydb:2136/local
    // forceScanSelect: execute all SELECT statements as scan queries to bypass YDB's per-transaction
    // memory limit (256KB in local container). The local container has no table statistics so YDB
    // wildly over-estimates memory for SELECT DISTINCT + ORDER BY. Scan queries have no such limit.
    public static final String DEFAULT_JDBC_URL = "jdbc:ydb:grpc://localhost:12136/local?forceScanSelect=true&scanQueryTxMode=FAKE_TX";

    public RemoteYdb() {
        super();
    }

    @Override
    public void beforeSuite(Config cf) {
        String jdbcUrl = System.getProperty(JDBC_URL_PROPERTY, DEFAULT_JDBC_URL);
        try (Connection conn = DriverManager.getConnection(jdbcUrl);
             Statement stmt = conn.createStatement()) {
            stmt.execute("DELETE FROM DATABASECHANGELOGLOCK");
            LOG.infof("RemoteYdb: cleared Liquibase lock at %s", jdbcUrl);
        } catch (Exception e) {
            LOG.infof("RemoteYdb: could not clear Liquibase lock (table may not exist yet): %s", e.getMessage());
        }
    }

    @Override
    public void updateConfig(Config cf) {
        String jdbcUrl = System.getProperty(JDBC_URL_PROPERTY, DEFAULT_JDBC_URL);
        LOG.infof("RemoteYdb: connecting to %s", jdbcUrl);
        configureYdbConnection(cf, jdbcUrl);
        LOG.infof("RemoteYdb: connected to %s", jdbcUrl);
    }
}