package tech.ydb.keycloak.liquibase

import liquibase.database.Database
import liquibase.exception.ValidationErrors
import liquibase.sql.Sql
import liquibase.sql.UnparsedSql
import liquibase.sqlgenerator.SqlGeneratorChain
import liquibase.sqlgenerator.core.AbstractSqlGenerator
import tech.ydb.liquibase.database.YdbDatabase

/**
 * Generates an UPDATE-based lock release for YDB.
 *
 * In Keycloak's default implementation the lock is released implicitly
 * by committing the transaction that holds the SELECT FOR UPDATE row lock.
 * Since YDB uses an explicit LOCKED = true column instead, the lock must
 * be released explicitly by setting LOCKED = false.
 */
class YdbUnlockSqlGenerator : AbstractSqlGenerator<YdbUnlockStatement>() {

    override fun getPriority(): Int = PRIORITY_DATABASE

    override fun supports(statement: YdbUnlockStatement, database: Database): Boolean =
        database is YdbDatabase

    override fun validate(
        statement: YdbUnlockStatement,
        database: Database,
        chain: SqlGeneratorChain<YdbUnlockStatement>
    ): ValidationErrors = ValidationErrors()

    override fun generateSql(
        statement: YdbUnlockStatement,
        database: Database,
        chain: SqlGeneratorChain<YdbUnlockStatement>
    ): Array<Sql> {
        val table = database.escapeTableName(
            database.liquibaseCatalogName,
            database.liquibaseSchemaName,
            database.databaseChangeLogLockTableName
        )
        val sql =
            """
            UPDATE $table
            SET
              LOCKED = false,
              LOCKGRANTED = null,
              LOCKEDBY = null
            WHERE
              ID = ${statement.id}
            """.trimIndent()
        return arrayOf(UnparsedSql(sql))
    }
}
