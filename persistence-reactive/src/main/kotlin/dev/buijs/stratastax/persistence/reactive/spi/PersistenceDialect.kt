/**
 * Copyright (c) 2021 - 2026 Buijs Software
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy of this software and associated
 * documentation files (the "Software"), to deal in the Software without restriction, including without limitation the
 * rights to use, copy, modify, merge, publish, distribute, sublicense, and/or sell copies of the Software, and to
 * permit persons to whom the Software is furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in all copies or substantial portions of the
 * Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR IMPLIED, INCLUDING BUT NOT LIMITED TO THE
 * WARRANTIES OF MERCHANTABILITY, FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE AUTHORS OR
 * COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR
 * OTHERWISE, ARISING FROM, OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE SOFTWARE.
 */
package dev.buijs.stratastax.persistence.reactive.spi

import dev.buijs.stratastax.persistence.core.api.TransactionOptions
import org.jooq.DSLContext
import org.jooq.SQLDialect
import org.jooq.conf.Settings
import org.jooq.impl.DSL

/**
 * Everything database-specific the persistence layer needs, so another SQL dialect can be plugged
 * in. [dev.buijs.stratastax.persistence.reactive.api.PostgresDialect] is the implementation in
 * use; see `docs/dialects.md` for what is inherently Postgres-only.
 */
public interface PersistenceDialect {

    /** The jOOQ dialect queries are rendered in. */
    public val sqlDialect: SQLDialect

    /**
     * The jOOQ settings this dialect needs, e.g. how identifiers are quoted. The persistence layer
     * relies on them (aliases are matched through [columnKey]), so build a [DSLContext] with [dsl].
     */
    public fun settings(): Settings

    /** A [DSLContext] for this dialect; [customize] may add settings of its own on top. */
    public fun dsl(customize: (Settings) -> Settings = { it }): DSLContext =
        DSL.using(sqlDialect, customize(settings()))

    /** Renders queries with the placeholder style of this dialect's driver. */
    public val renderer: SqlRenderer

    /** The SQL of the transaction-control statements. */
    public val transactions: TransactionSql

    /**
     * The key a result column [name] is matched by: the identifier as the database folds it when
     * it is not quoted (lowercase on Postgres).
     */
    public fun columnKey(name: String): String

    /** Whether [word] is reserved, so it cannot be used unquoted as an alias. */
    public fun isReserved(word: String): Boolean
}

/** The transaction-control statements of a [PersistenceDialect]. */
public interface TransactionSql {

    /** Starts a transaction with [options]' isolation level and access mode. */
    public fun begin(options: TransactionOptions): String

    public fun commit(): String = "COMMIT"

    public fun rollback(): String = "ROLLBACK"

    public fun savepoint(name: String): String = "SAVEPOINT $name"

    public fun releaseSavepoint(name: String): String = "RELEASE SAVEPOINT $name"

    public fun rollbackToSavepoint(name: String): String = "ROLLBACK TO SAVEPOINT $name"
}
