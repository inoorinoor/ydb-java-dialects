package tech.ydb.keycloak.liquibase

import liquibase.database.Database
import liquibase.exception.ValidationErrors
import liquibase.sql.Sql
import liquibase.sql.UnparsedSql
import liquibase.sqlgenerator.SqlGeneratorChain
import liquibase.sqlgenerator.core.AbstractSqlGenerator
import tech.ydb.liquibase.database.YdbDatabase

/**
 * Generates an atomic UPDATE-based lock acquisition for YDB.
 *
 * Keycloak's default implementation uses SELECT FOR UPDATE to hold a pessimistic
 * row-level lock for the duration of the transaction. YDB does not support
 * SELECT FOR UPDATE, so instead we use a conditional UPDATE:
 *
 *   UPDATE ... SET LOCKED = true WHERE ID = ? AND LOCKED = false
 *
 * If the UPDATE affects 1 row → lock acquired.
 * If the UPDATE affects 0 rows → lock is held by another process, retry later.
 *
 * Unlike SELECT FOR UPDATE (which holds a lock implicitly until commit),
 * this approach requires an explicit unlock — see YdbUnlockSqlGenerator.
 */
class YdbLockSqlGenerator : AbstractSqlGenerator<YdbLockStatement>() {

    override fun getPriority(): Int = PRIORITY_DATABASE

    override fun supports(statement: YdbLockStatement, database: Database): Boolean =
        database is YdbDatabase

    override fun validate(
        statement: YdbLockStatement,
        database: Database,
        chain: SqlGeneratorChain<YdbLockStatement>
    ): ValidationErrors = ValidationErrors()

    override fun generateSql(
        statement: YdbLockStatement,
        database: Database,
        chain: SqlGeneratorChain<YdbLockStatement>
    ): Array<Sql> {
        val table = database.escapeTableName(
            database.liquibaseCatalogName,
            database.liquibaseSchemaName,
            database.databaseChangeLogLockTableName
        )
        val escapedLockedBy = statement.lockedBy.replace("'", "''")
        val sql =
          """
            UPDATE $table 
            SET 
              LOCKED = true, 
              LOCKGRANTED = CurrentUtcDatetime(), 
              LOCKEDBY = '$escapedLockedBy' 
            WHERE 
              ID = ${statement.id} AND 
              LOCKED = false
            """.trimIndent()
        return arrayOf(UnparsedSql(sql))
    }
}
