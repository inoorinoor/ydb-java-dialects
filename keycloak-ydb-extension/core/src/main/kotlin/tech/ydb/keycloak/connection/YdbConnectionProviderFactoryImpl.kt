package tech.ydb.keycloak.connection

import jakarta.persistence.EntityManagerFactory
import jakarta.persistence.SynchronizationType.SYNCHRONIZED
import liquibase.GlobalConfiguration
import org.hibernate.cfg.AvailableSettings
import org.jboss.logging.Logger
import org.keycloak.Config
import org.keycloak.ServerStartupError
import org.keycloak.connections.jpa.DefaultJpaConnectionProvider
import org.keycloak.connections.jpa.JpaConnectionProvider
import org.keycloak.connections.jpa.JpaConnectionProviderFactory
import org.keycloak.connections.jpa.JpaKeycloakTransaction
import org.keycloak.connections.jpa.support.EntityManagerProxy
import org.keycloak.connections.jpa.updater.JpaUpdaterProvider
import org.keycloak.connections.jpa.updater.JpaUpdaterProvider.Status.EMPTY
import org.keycloak.connections.jpa.updater.JpaUpdaterProvider.Status.VALID
import org.keycloak.connections.jpa.util.JpaUtils
import org.keycloak.migration.MigrationModelManager
import org.keycloak.models.KeycloakSession
import org.keycloak.models.KeycloakSessionFactory
import org.keycloak.models.dblock.DBLockManager
import org.keycloak.models.dblock.DBLockProvider
import org.keycloak.models.utils.KeycloakModelUtils
import org.keycloak.provider.ServerInfoAwareProviderFactory
import tech.ydb.hibernate.dialect.YdbDialect
import tech.ydb.jdbc.YdbDriver
import tech.ydb.keycloak.config.ProviderConfig.PROVIDER_ID
import tech.ydb.keycloak.config.ProviderConfig.PROVIDER_PRIORITY
import tech.ydb.keycloak.connection.YdbConnectionProviderFactoryImpl.Companion.MigrationStrategy.*
import java.io.File
import java.sql.Connection
import java.sql.DriverManager
import java.util.*
import kotlin.properties.Delegates

class YdbConnectionProviderFactoryImpl : JpaConnectionProviderFactory, ServerInfoAwareProviderFactory {

  private val logger: Logger = Logger.getLogger(YdbConnectionProviderFactoryImpl::class.java)

  private lateinit var config: Config.Scope

  private var jtaEnabled by Delegates.notNull<Boolean>()

  @Volatile
  private lateinit var entityManagerFactory: EntityManagerFactory

  override fun create(session: KeycloakSession): JpaConnectionProvider {
    val emf = getOrCreateEntityManagerFactory(session)

    val em = if (!jtaEnabled) {
      logger.trace("enlisting EntityManager in JpaKeycloakTransaction")
      emf.createEntityManager()
    } else {
      emf.createEntityManager(SYNCHRONIZED)
    }

    if (!jtaEnabled) {
      session.transactionManager.enlist(JpaKeycloakTransaction(em))
    }
    return DefaultJpaConnectionProvider(EntityManagerProxy.create(session, em, true))
  }

  override fun init(scope: Config.Scope) {
    config = scope
  }

  override fun postInit(factory: KeycloakSessionFactory) {
    checkJtaEnabled(factory)

    val schema = getSchema()
    connection.use { connection ->
      factory.create().use { session ->
        createOrUpdateSchema(schema, connection, session)
      }
    }
    analyzeTablesIfEnabled()
    factory.create().use { session -> getOrCreateEntityManagerFactory(session) }

    KeycloakModelUtils.runJobInTransaction(factory) { session -> migrateModel(session) }
  }

  private fun resolveJdbcUrl(): String = requireNotNull(config["jdbcUrl"]) {
    "YDB JDBC URL is required"
  }

  private fun createOrUpdateSchema(
    schema: String?,
    connection: Connection,
    session: KeycloakSession
  ) {
    val strategy: MigrationStrategy = getMigrationStrategy()
    val initializeEmpty = config.getBoolean("initializeEmpty", true)
    val databaseUpdateFile: File = getDatabaseUpdateFile()

    // actually it is QuarkusJpaUpdaterProvider and it works
    val updater = session.getProvider(JpaUpdaterProvider::class.java)

    val status = updater.validate(connection, schema)

    if (status == VALID) {
      logger.debug("Database is up-to-date")
    } else if (status == EMPTY) {
      if (initializeEmpty) {
        update(connection, schema, session, updater)
      } else {
        when (strategy) {
          UPDATE -> update(connection, schema, session, updater)
          MANUAL -> {
            export(connection, schema, databaseUpdateFile, session, updater)
            throw ServerStartupError(
              "Database not initialized, please initialize database with " + databaseUpdateFile.getAbsolutePath(),
              false
            )
          }

          VALIDATE -> throw ServerStartupError(
            "Database not initialized, please enable database initialization",
            false
          )
        }
      }
    } else {
      when (strategy) {
        UPDATE -> update(connection, schema, session, updater)
        MANUAL -> {
          export(connection, schema, databaseUpdateFile, session, updater)
          throw ServerStartupError(
            "Database not up-to-date, please migrate database with " + databaseUpdateFile.getAbsolutePath(),
            false
          )
        }

        VALIDATE -> throw ServerStartupError(
          "Database not up-to-date, please enable database migration",
          false
        )
      }
    }
  }

  override fun close() {
    if (::entityManagerFactory.isInitialized) {
      entityManagerFactory.close()
    }
  }

  override fun getId(): String = PROVIDER_ID

  override fun order(): Int = PROVIDER_PRIORITY

  override fun getConnection(): Connection {
    try {
      val url = resolveJdbcUrl()
      val driver = YdbDriver::class.java.name
      Class.forName(driver)
      return DriverManager.getConnection(url)
    } catch (e: Exception) {
      throw RuntimeException("Failed to connect to database", e)
    }
  }

  override fun getSchema(): String? {
    val schema = config.get("schema")?.takeIf { it.isNotBlank() }
    if (schema?.contains("-") == true
      && !System.getProperty(GlobalConfiguration.PRESERVE_SCHEMA_CASE.key).toBoolean()
    ) {
      System.setProperty(GlobalConfiguration.PRESERVE_SCHEMA_CASE.key, "true")
      logger.warnf(
        "The passed schema '%s' contains a dash. Setting liquibase config option PRESERVE_SCHEMA_CASE to true. See https://github.com/keycloak/keycloak/issues/20870 for more information.",
        schema
      )
    }
    return schema
  }

  // TODO: can be added more info for info in admin console
  override fun getOperationalInfo(): Map<String, String> = mapOf(
    "YDB" to "enabled",
  )


  private fun checkJtaEnabled(factory: KeycloakSessionFactory) {
    // for now, we do not need jta
    // we will use resource local transaction
    jtaEnabled = false
  }

  private fun getOrCreateEntityManagerFactory(session: KeycloakSession): EntityManagerFactory {
    if (::entityManagerFactory.isInitialized) {
      return entityManagerFactory
    }
    synchronized(this) {
      if (::entityManagerFactory.isInitialized) {
        return entityManagerFactory
      }

      val properties = buildPropertiesFromScope()

      entityManagerFactory = JpaUtils.createEntityManagerFactory(session, PERSISTENCE_UNIT_NAME, properties, jtaEnabled)
      logger.info("YDB EntityManagerFactory created via JpaUtils")
      return entityManagerFactory
    }
  }

  private fun buildPropertiesFromScope(): MutableMap<String, Any> {
    val properties = mutableMapOf<String, Any>()

    properties[AvailableSettings.JAKARTA_JDBC_URL] = resolveJdbcUrl()
    properties[AvailableSettings.JAKARTA_JDBC_DRIVER] = YdbDriver::class.java.name

    getSchema()?.let { properties[JpaUtils.HIBERNATE_DEFAULT_SCHEMA] = it }

    properties["hibernate.dialect"] = YdbDialect::class.java.name

    properties["hibernate.show_sql"] = config.getBoolean("showSql", false)
    properties["hibernate.format_sql"] = config.getBoolean("formatSql", true)

    val globalStatsInterval = config.getInt("globalStatsInterval", -1)
    if (globalStatsInterval != -1) {
      properties.put("hibernate.generate_statistics", true)
    }

    val classLoaders = ArrayList<ClassLoader?>()

    if (properties.containsKey(AvailableSettings.CLASSLOADERS)) {
      classLoaders.addAll(properties.get(AvailableSettings.CLASSLOADERS) as Collection<ClassLoader?>)
    }
    classLoaders.add(javaClass.classLoader)
    properties.put(AvailableSettings.CLASSLOADERS, classLoaders)

    return properties
  }

  private fun update(
    connection: Connection?,
    schema: String?,
    session: KeycloakSession,
    updater: JpaUpdaterProvider
  ) {
    KeycloakModelUtils.runJobInTransaction(session.keycloakSessionFactory) { lockSession ->
      val dbLockManager = DBLockManager(lockSession)
      val dbLock2 = dbLockManager.dbLock
      dbLock2.waitForLock(DBLockProvider.Namespace.DATABASE)
      try {
        updater.update(connection, schema)
      } finally {
        dbLock2.releaseLock()
      }
    }
  }

  private fun export(
    connection: Connection?,
    schema: String?,
    databaseUpdateFile: File?,
    session: KeycloakSession,
    updater: JpaUpdaterProvider
  ) {
    KeycloakModelUtils.runJobInTransaction(session.keycloakSessionFactory) { lockSession ->
      val dbLockManager = DBLockManager(lockSession)
      val dbLock2 = dbLockManager.dbLock
      dbLock2.waitForLock(DBLockProvider.Namespace.DATABASE)
      try {
        updater.export(connection, schema, databaseUpdateFile)
      } finally {
        dbLock2.releaseLock()
      }
    }
  }

  private fun getMigrationStrategy(): MigrationStrategy {
    var migrationStrategy = config.get("migrationStrategy")
    if (migrationStrategy == null) {
      // !!! comment from keycloak code
      // Support 'databaseSchema' for backwards compatibility
      migrationStrategy = config.get("databaseSchema")
    }

    return if (migrationStrategy != null) {
      MigrationStrategy.valueOf(migrationStrategy.uppercase(Locale.getDefault()))
    } else {
      UPDATE
    }
  }

  private fun migrateModel(session: KeycloakSession) {
    // !!! comment from keycloak code
    // Using a lock to prevent concurrent migration in concurrently starting nodes
    val dbLockManager = DBLockManager(session)
    val dbLock = dbLockManager.dbLock
    dbLock.waitForLock(DBLockProvider.Namespace.DATABASE)
    try {
      KeycloakModelUtils.runJobInTransaction(
        session.keycloakSessionFactory
      ) { session: KeycloakSession? -> MigrationModelManager.migrate(session) }
    } finally {
      dbLock.releaseLock()
    }
  }

  private fun getDatabaseUpdateFile(): File {
    val databaseUpdateFile = config.get("migrationExport", "keycloak-database-update.sql")
    return File(databaseUpdateFile)
  }

  /**
   * Runs ANALYZE on all tables after schema creation/update to give YDB accurate cardinality
   * statistics. Without statistics, YDB uses UINT32_MAX as default row count estimate, which
   * causes "Mkql memory limit exceeded" errors for SELECT DISTINCT ... ORDER BY queries.
   *
   * Enabled via config option "analyzeOnStartup=true".
   */
  private fun analyzeTablesIfEnabled() {
    if (!config.getBoolean("analyzeOnStartup", false)) {
      return
    }
    val dbPath = extractDatabasePath(resolveJdbcUrl())
    logger.infof("Running ANALYZE on YDB tables (path prefix: %s) to collect query planner statistics...", dbPath)
    try {
      connection.use { conn ->
        conn.createStatement().use { stmt ->
          conn.metaData.getTables(null, null, "%", arrayOf("TABLE")).use { rs ->
            while (rs.next()) {
              val tableName = rs.getString("TABLE_NAME")
              val fullPath = "`$dbPath/$tableName`"
              try {
                stmt.execute("ANALYZE $fullPath")
                logger.debugf("ANALYZE completed: %s", fullPath)
              } catch (e: Exception) {
                logger.warnf("ANALYZE failed for %s: %s", fullPath, e.message)
              }
            }
          }
        }
      }
      logger.info("ANALYZE completed for all YDB tables")
    } catch (e: Exception) {
      logger.warnf(e, "Failed to run ANALYZE on YDB tables, query planner will use default estimates")
    }
  }

  private fun extractDatabasePath(jdbcUrl: String): String {
    // jdbc:ydb:grpc://host:port/database?params → /database
    val withoutScheme = jdbcUrl
      .removePrefix("jdbc:ydb:grpcs://")
      .removePrefix("jdbc:ydb:grpc://")
    val dbWithParams = withoutScheme.substringAfter("/")
    return "/" + dbWithParams.substringBefore("?")
  }

  private companion object {
    private enum class MigrationStrategy {
      UPDATE, VALIDATE, MANUAL
    }

    const val PERSISTENCE_UNIT_NAME = "keycloak-default"
  }
}
