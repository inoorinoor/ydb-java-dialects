package tech.ydb.keycloak.liquibase

import liquibase.Scope
import liquibase.exception.DatabaseException
import liquibase.exception.UnexpectedLiquibaseException
import liquibase.executor.ExecutorService
import liquibase.lockservice.StandardLockService
import liquibase.statement.SqlStatement
import liquibase.statement.core.CreateDatabaseChangeLogLockTableStatement
import liquibase.statement.core.LockDatabaseChangeLogStatement
import liquibase.statement.core.RawSqlStatement
import liquibase.util.NetUtil
import org.jboss.logging.Logger
import org.keycloak.common.util.Time
import org.keycloak.common.util.reflections.Reflections
import org.keycloak.connections.jpa.updater.liquibase.LiquibaseConstants
import org.keycloak.connections.jpa.updater.liquibase.lock.CustomInitializeDatabaseChangeLogLockTableStatement
import org.keycloak.connections.jpa.updater.liquibase.lock.CustomLockDatabaseChangeLogStatement
import org.keycloak.connections.jpa.updater.liquibase.lock.LockRetryException
import org.keycloak.models.dblock.DBLockProvider
import tech.ydb.liquibase.lockservice.StandardLockServiceYdb

class YdbLockService : StandardLockServiceYdb() {
  private val log: Logger = Logger.getLogger(YdbLockService::class.java)

  override fun init() {
    val executor = Scope.getCurrentScope().getSingleton(ExecutorService::class.java)
      .getExecutor(LiquibaseConstants.JDBC_EXECUTOR, database)

    if (!isDatabaseChangeLogLockTableCreated) {
      try {
        log.trace("Create Database Lock Table")
        executor.execute(CreateDatabaseChangeLogLockTableStatement())
        database.commit()
      } catch (de: DatabaseException) {
        log.warn("Failed to create lock table. Maybe other transaction created in the meantime. Retrying...", de)
        log.trace(de.message, de)
        database.rollback()
        throw LockRetryException(de)
      }

      log.debug("Created database lock table with name: ${escapeTableName()}")

      try {
        val field = Reflections.findDeclaredField(StandardLockService::class.java, "hasDatabaseChangeLogLockTable")
        Reflections.setAccessible(field)
        field.set(this@YdbLockService, true)
      } catch (iae: IllegalAccessException) {
        throw RuntimeException(iae)
      }
    }

    try {
      val currentIds = currentIdsInDatabaseChangeLogLockTable()
      val customNamespaceIds =
        DBLockProvider.Namespace.entries.map { it.getId() }.toSet()
      if (!currentIds.containsAll(customNamespaceIds)) {
        log.trace("Initialize Database Lock Table, current locks $currentIds")
        executor.execute(CustomInitializeDatabaseChangeLogLockTableStatement(currentIds))
        database.commit()

        log.debug("Initialized record in the database lock table")
      }
    } catch (de: DatabaseException) {
      log.warn(
        "Failed to insert first record to the lock table. Maybe other transaction inserted in the meantime. Retrying...",
        de
      )
      log.trace(de.message, de)
      database.rollback()
      throw LockRetryException(de)
    }
  }

  private fun currentIdsInDatabaseChangeLogLockTable(): Set<Int> = try {
    val executor = Scope.getCurrentScope().getSingleton(ExecutorService::class.java)
      .getExecutor(LiquibaseConstants.JDBC_EXECUTOR, database)
    val sqlStatement: SqlStatement = RawSqlStatement("SELECT ${escapeIdColumnName()} FROM ${escapeTableName()}")

    executor.queryForList(sqlStatement)
      .map { columnMap -> (columnMap["ID"] as Number).toInt() }
      .toSet()
      .also { database.commit() }
  } catch (ulie: UnexpectedLiquibaseException) {
    throw ulie.cause?.takeIf { it is DatabaseException } ?: ulie
  }

  override fun waitForLock() {
    waitForLock(LockDatabaseChangeLogStatement())
  }

  fun waitForLock(lock: DBLockProvider.Namespace) {
    waitForLock(CustomLockDatabaseChangeLogStatement(lock.id))
  }

  private fun waitForLock(lockStmt: LockDatabaseChangeLogStatement) {
    var locked = false
    val startTime = Time.toMillis(Time.currentTime().toLong())
    val timeToGiveUp = startTime + (getChangeLogLockWaitTime())
    var nextAttempt = true

    while (nextAttempt) {
      locked = acquireLock(lockStmt)
      if (!locked) {
        val remainingTime = ((timeToGiveUp / 1000).toInt()) - Time.currentTime()
        if (remainingTime > 0) {
          log.debug("Will try to acquire log another time. Remaining time: $remainingTime seconds")
        } else {
          nextAttempt = false
        }
      } else {
        nextAttempt = false
      }
    }

    if (!locked) {
      val timeout = ((getChangeLogLockWaitTime() / 1000).toInt())
      throw IllegalStateException("Could not acquire change log lock within specified timeout $timeout seconds.  Currently locked by other transaction")
    }
  }

  override fun acquireLock(): Boolean {
    return acquireLock(LockDatabaseChangeLogStatement())
  }

  private fun acquireLock(lockStmt: LockDatabaseChangeLogStatement): Boolean {
    if (hasChangeLogLock) {
      return true
    }

    val executor = Scope.getCurrentScope().getSingleton(ExecutorService::class.java)
      .getExecutor(LiquibaseConstants.JDBC_EXECUTOR, database)

    try {
      database.rollback()
      this.init()
    } catch (de: DatabaseException) {
      throw IllegalStateException("Failed to retrieve lock", de)
    }

    val id = if (lockStmt is CustomLockDatabaseChangeLogStatement) lockStmt.id else DEFAULT_LOCK_ID
    val lockedBy = "${NetUtil.getLocalHostName()} (${NetUtil.getLocalHostAddress()}):${Thread.currentThread().threadId()}"

    return try {
      log.debug("Trying to acquire lock id=$id")
      val affected = executor.update(YdbLockStatement(id, lockedBy))

      if (affected > 0) {
        database.commit()

        val actualLockedBy = executor
          .queryForList(RawSqlStatement("SELECT LOCKEDBY FROM ${escapeTableName()} WHERE ID = $id AND LOCKED = true"))
          .also { database.commit() }
          .firstOrNull()
          ?.get("LOCKEDBY") as? String

        if (actualLockedBy == lockedBy) {
          hasChangeLogLock = true
          database.setCanCacheLiquibaseTableInfo(true)
          log.debug("Successfully acquired lock id=$id")
          true
        } else {
          log.debug("Lock id=$id verification failed (lockedBy in DB: $actualLockedBy)")
          false
        }
      } else {
        database.rollback()
        log.debug("Lock id=$id is held by another transaction")
        false
      }
    } catch (de: DatabaseException) {
      log.warn("Lock acquisition failed, will retry. Details: ${de.message}")
      try {
        database.rollback()
      } catch (_: DatabaseException) {
        // no operations
      }
      false
    }
  }


  fun tryReleaseLock(lockId: Int) {
    log.debug("Going to release database lock id=$lockId")
    val executor = Scope.getCurrentScope().getSingleton(ExecutorService::class.java)
      .getExecutor(LiquibaseConstants.JDBC_EXECUTOR, database)

    database.rollback()
    try {
      val affected = executor.update(YdbUnlockStatement(lockId))
      if (affected != 1) {
        log.warn("Release lock id=$lockId affected $affected rows — expected exactly 1")
      }
      database.commit()
    } catch (e: Exception) {
      throw RuntimeException("Failed to release lock id=$lockId", e)
    }
  }

  fun cleanupLockState() {
    try {
      hasChangeLogLock = false
      database.setCanCacheLiquibaseTableInfo(false)
      database.rollback()
    } catch (_: DatabaseException) {
      // no operations
    }
  }

  override fun releaseLock() {
    releaseLock(DEFAULT_LOCK_ID)
  }

  fun releaseLock(lockId: Int) {
    try {
      if (hasChangeLogLock) {
        tryReleaseLock(lockId)
      } else {
        log.warn("Attempt to release lock, which is not owned by current transaction")
      }
    } catch (e: Exception) {
      log.error("Database error during release lock", e)
    } finally {
      cleanupLockState()
    }
  }

  private fun escapeIdColumnName(): String? = database.escapeColumnName(
    database.liquibaseCatalogName,
    database.liquibaseSchemaName,
    database.databaseChangeLogLockTableName,
    "ID"
  )

  private fun escapeTableName(): String = database.escapeTableName(
    database.liquibaseCatalogName,
    database.liquibaseSchemaName,
    database.databaseChangeLogLockTableName
  )

  companion object {
    private val DEFAULT_LOCK_ID = DBLockProvider.Namespace.DATABASE.id
  }
}
