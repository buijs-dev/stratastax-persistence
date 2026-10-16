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
package dev.buijs.stratastax.persistence.reactive.api

import dev.buijs.stratastax.persistence.core.api.QueryExecution
import dev.buijs.stratastax.persistence.core.api.QueryOperation
import dev.buijs.stratastax.persistence.core.api.RenderedQuery
import dev.buijs.stratastax.persistence.core.spi.QueryExecutionListener
import dev.buijs.stratastax.persistence.core.spi.RowContext
import dev.buijs.stratastax.persistence.reactive.app.ColumnIndex
import dev.buijs.stratastax.persistence.reactive.app.PostgresSqlRenderer
import dev.buijs.stratastax.persistence.reactive.app.VertxRowContext
import io.smallrye.mutiny.coroutines.awaitSuspending
import io.vertx.core.json.JsonArray
import io.vertx.core.json.JsonObject
import io.vertx.mutiny.sqlclient.Row
import io.vertx.mutiny.sqlclient.RowSet
import io.vertx.mutiny.sqlclient.SqlClient
import io.vertx.mutiny.sqlclient.Tuple
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import org.jooq.JSON
import org.jooq.JSONB
import org.jooq.Query
import kotlin.time.Duration
import kotlin.time.TimeSource

/**
 * [QueryExecutor] implementation over a Vert.x Mutiny [SqlClient] - jOOQ here only builds the
 * [Query], [renderer] turns it into the SQL/bind values an actual driver runs, and this class is
 * what actually runs it reactively, hands rows back as [RowContext] (via [VertxRowContext]), and
 * reports each execution to every [listeners] entry.
 *
 * Every statement is reported - also a failed one (with its error) and the transaction-control
 * statements [executeStatement] runs - tagged with [transactionId] when this executor is bound to
 * one transaction's connection, so a listener sees a transaction's complete statement sequence.
 */
class VertxQueryExecutor(
    private val client: SqlClient,
    private val renderer: SqlRenderer = PostgresSqlRenderer(),
    private val listeners: List<QueryExecutionListener> = emptyList(),
    private val transactionId: String? = null,
) : QueryExecutor {

    override suspend fun executeAndGetAll(query: Query): List<RowContext> {
        val rows = execute(query)
        // One column index for the whole result set: names resolve once, not per row.
        val index = ColumnIndex(rows.columnsNames())
        return buildList { rows.forEach { add(VertxRowContext(it, index)) } }
    }

    override suspend fun executeAndGetFirst(query: Query): RowContext? {
        val iterator = execute(query).iterator()
        return if (iterator.hasNext()) VertxRowContext(iterator.next()) else null
    }

    override suspend fun exists(query: Query): Boolean = execute(query).iterator().hasNext()

    override suspend fun executeAndGetCount(query: Query): Long {
        val iterator = execute(query).iterator()
        return if (iterator.hasNext()) iterator.next().getLong(0) else 0L
    }

    override suspend fun executeUpdate(query: Query): Int = execute(query).rowCount()

    override suspend fun executeReturning(query: Query): RowContext? {
        val iterator = execute(query).iterator()
        return if (iterator.hasNext()) VertxRowContext(iterator.next()) else null
    }

    private suspend fun execute(query: Query): RowSet<Row> {
        val rendered = renderer.render(query)
        return observed(rendered) {
            client
                .preparedQuery(rendered.sql)
                .execute(Tuple.from(rendered.parameters.map(::vertxBindValue)))
                .awaitSuspending()
        }
    }

    /**
     * Runs a parameterless, non-jOOQ statement - `BEGIN`, `SAVEPOINT ...`, `COMMIT`, ... - on this
     * executor's connection, reported to [listeners] exactly like any query.
     */
    internal suspend fun executeStatement(sql: String) {
        observed(RenderedQuery(sql, emptyList())) { client.query(sql).execute().awaitSuspending() }
    }

    private suspend fun observed(
        rendered: RenderedQuery,
        run: suspend () -> RowSet<Row>,
    ): RowSet<Row> {
        val started = TimeSource.Monotonic.markNow()
        val rows =
            try {
                run()
            } catch (e: Throwable) {
                // Report even when cancelled: the statement may well have reached the database.
                withContext(NonCancellable) {
                    report(rendered, started.elapsedNow(), rowCount = null, error = e)
                }

                throw e
            }

        report(rendered, started.elapsedNow(), rowCount = rows.rowCount(), error = null)
        return rows
    }

    private suspend fun report(
        rendered: RenderedQuery,
        duration: Duration,
        rowCount: Int?,
        error: Throwable?,
    ) {
        val execution =
            QueryExecution(
                query = rendered,
                operation = QueryOperation.of(rendered.sql),
                duration = duration,
                rowCount = rowCount,
                transactionId = transactionId,
                error = error,
            )
        listeners.forEach { it.executed(execution) }
    }

    /**
     * jOOQ's own `JSON`/`JSONB` wrapper types (produced whenever a bind value targets a
     * `json`/`jsonb` column - see e.g.
     * `dev.buijs.stratastax.codegen.features.persistence.binding.CreateUpdateBindingUnwrapResolver`'s
     * generated `org.jooq.JSONB.valueOf(...)` calls) aren't a type Vert.x's own PG encoder
     * recognizes at all. The raw JSON text alone (a plain [String]) isn't enough either - Postgres
     * accepts it fine (an implicit text-to-json/jsonb cast), but Vert.x's *own* client-side type
     * tracking then treats that parameter/column as plain text for the rest of the round trip, so
     * decoding a returned row's own json/jsonb column back via `Row.getJsonObject` throws a
     * `ClassCastException` (`String` can't cast to `JsonObject`) - confirmed by a real Postgres
     * integration test. Vert.x's own [JsonObject]/[JsonArray] are what its encoder/decoder natively
     * agree on for a json/jsonb column in both directions - matches how the *read* side already
     * decodes one via `Row.getJsonObject`/ `.getJsonArray`
     * (`VertxRowContext.mapOrNull`/`.objectListOrNull`). Every other bind value passes through
     * unchanged.
     */
    private fun vertxBindValue(value: Any?): Any? =
        when (value) {
            is JSON -> decodeJson(value.data())
            is JSONB -> decodeJson(value.data())
            else -> value
        }

    private fun decodeJson(raw: String): Any =
        when (raw.trimStart().firstOrNull()) {
            '[' -> JsonArray(raw)
            else -> JsonObject(raw)
        }
}
