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

import dev.buijs.stratastax.entity.core.api.SortDirection
import dev.buijs.stratastax.persistence.core.api.TransactionOptions
import io.vertx.core.json.JsonObject
import java.net.URI
import java.net.URL
import java.time.Instant
import java.time.ZoneOffset

/**
 * Everything database-specific the persistence layer needs, so another SQL dialect can be plugged
 * in from a module of its own: the executors, transactions and search rendering of
 * `persistence-reactive` only ever reach the database through it. `persistence-postgres` holds the
 * PostgreSQL implementation; see `docs/dialects.md` for writing another one.
 */
public interface PersistenceDialect {
    /** The SQL of the transaction-control statements. */
    public val transactions: TransactionSql

    /** The syntax a [SearchSql] of this dialect is rendered with. */
    public val search: SearchSyntax

    /**
     * The key a result column [name] is matched by: the identifier as the database folds it when
     * it is not quoted (lowercase on Postgres).
     */
    public fun columnKey(name: String): String

    /**
     * A statement parameter as the Vert.x client of this database binds it. By default a [Map]
     * becomes a [JsonObject] (a JSON parameter), a [URI] or [URL] its text, an enum its name and an
     * [Instant] an [java.time.OffsetDateTime] in UTC; anything else is bound as it is. Override it
     * for the types a client encodes differently, and call `super` for the rest.
     */
    public fun bindValue(value: Any?): Any? =
        when (value) {
            is Map<*, *> -> {
                JsonObject(value.entries.associate { (key, element) -> key.toString() to element })
            }

            is URI, is URL -> {
                value.toString()
            }

            is Enum<*> -> {
                value.name
            }

            is Instant -> {
                value.atOffset(ZoneOffset.UTC)
            }

            else -> {
                value
            }
        }
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

/**
 * The pieces of SQL a search is rendered from at runtime: parameters, comparisons, the sort, the
 * limit and row locking. How they are combined - the conditions, the keyset seek, the `EXISTS`
 * along a relation - is the same for every dialect.
 *
 * A search sorts NULL as the largest value; [orderBy] must render that order.
 */
public interface SearchSyntax {
    /** The placeholder of parameter [position] (1-based) of SQL type [type], e.g. `$3::uuid` or `?`. */
    public fun parameter(
        position: Int,
        type: SqlType,
    ): String

    /**
     * The placeholder of parameter [position] bound as an array of [type] values (e.g.
     * `$3::uuid[]`), or `null` when this dialect does not bind arrays of [type]: several values are
     * then written as an `IN (...)` list.
     */
    public fun arrayParameter(
        position: Int,
        type: SqlType,
    ): String? = null

    /** [column] equals one element of the [array] parameter, e.g. `c = ANY($1::uuid[])`. */
    public fun anyOf(
        column: String,
        array: String,
    ): String = "$column = ANY($array)"

    /** [column] differs from every element of the [array] parameter, e.g. `c <> ALL($1::uuid[])`. */
    public fun noneOf(
        column: String,
        array: String,
    ): String = "$column <> ALL($array)"

    /**
     * [column] matches the `LIKE` [pattern] parameter, ignoring case with [ignoreCase]. The pattern
     * escapes `%`, `_` and itself with a backslash.
     */
    public fun like(
        column: String,
        pattern: String,
        ignoreCase: Boolean,
    ): String

    /** [column] in [direction], with NULL sorting as the largest value. */
    public fun orderBy(
        column: String,
        direction: SortDirection,
    ): String

    /** The clause limiting a statement to the [parameter] number of rows, e.g. `LIMIT $4::integer`. */
    public fun limit(parameter: String): String = "LIMIT $parameter"

    /** [lockClause] (e.g. `FOR UPDATE OF t`), skipping the rows other transactions locked. */
    public fun skipLocked(lockClause: String): String = "$lockClause SKIP LOCKED"

    /** Whether a cursor seek may be one row-value comparison: `(a, b) > ($1, $2)`. */
    public val rowValueComparison: Boolean
        get() = false

    /** A condition that always holds: what an empty `AND` renders as. */
    public val alwaysTrue: String
        get() = "TRUE"

    /** A condition that never holds: what an empty `OR` renders as. */
    public val alwaysFalse: String
        get() = "FALSE"
}
