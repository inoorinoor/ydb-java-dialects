package tech.ydb.keycloak.testsuite.parameters;

import org.jboss.logging.Logger;
import tech.ydb.keycloak.testsuite.Config;
import tech.ydb.test.integration.YdbHelper;
import tech.ydb.test.integration.YdbHelperFactory;

public class Ydb extends AbstractYdbParameters {

    private static final Logger LOG = Logger.getLogger(Ydb.class);

    private YdbHelper ydbHelper;

    public Ydb() {
        super();
    }

    @Override
    public void beforeSuite(Config cf) {
        YdbHelperFactory factory = YdbHelperFactory.getInstance();
        if (!factory.isEnabled()) {
            LOG.warn("YDB helper is not available - tests will be skipped");
            return;
        }
        LOG.info("Creating YDB helper for Keycloak model tests");
        ydbHelper = factory.createHelper();
        if (ydbHelper == null) {
            LOG.warn("Failed to create YDB helper - tests will be skipped");
        }
    }

    @Override
    public void afterSuite() {
        if (ydbHelper != null) {
            try {
                ydbHelper.close();
            } catch (Exception e) {
                LOG.warnf(e, "Error closing YDB helper");
            }
            ydbHelper = null;
        }
    }

    @Override
    public void updateConfig(Config cf) {
        String jdbcUrl = ydbHelper != null ? buildJdbcUrl(ydbHelper) : null;
        configureYdbConnection(cf, jdbcUrl);
    }

    private static String buildJdbcUrl(YdbHelper helper) {
        return "jdbc:ydb:" +
                (helper.useTls() ? "grpcs://" : "grpc://") +
                helper.endpoint() +
                helper.database();
    }
}