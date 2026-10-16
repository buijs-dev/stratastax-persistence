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

import dev.buijs.stratastax.persistence.core.spi.QueryExecution
import dev.buijs.stratastax.persistence.core.spi.QueryExecutionListener
import dev.buijs.stratastax.persistence.core.spi.QueryOperation
import dev.buijs.stratastax.persistence.core.spi.RenderedQuery
import dev.buijs.stratastax.persistence.core.spi.RowContext
import dev.buijs.stratastax.persistence.reactive.internal.ColumnIndex
import dev.buijs.stratastax.persistence.reactive.internal.VertxRowContext
import dev.buijs.stratastax.persistence.reactive.spi.PersistenceDialect
import dev.buijs.stratastax.persistence.reactive.spi.QueryExecutor
import dev.buijs.stratastax.persistence.reactive.spi.SqlRenderer
import io.github.oshai.kotlinlogging.KotlinLogging
import io.smallrye.mutiny.coroutines.awaitSuspending
import io.vertx.core.json.Json
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
import java.util.concurrent.atomic.AtomicInteger
import kotlin.coroutines.cancellation.CancellationException
import kotlin.time.Duration
import kotlin.time.TimeSource

private val log = KotlinLogging.logger {}

/**
 * A [QueryExecutor] over a Vert.x Mutiny [SqlClient] (a pool, or one connection): renders each
 * query with [renderer], runs it, hands its rows back as [RowContext]s and reports it to every one
 * of [listeners] - also a failed statement and the transaction-control statements of a
 * transaction, tagged with [transactionId] when this executor is bound to one.
 *
 * A listener that throws is logged and otherwise ignored: it never changes the outcome of a
 * statement or a transaction.
 *
 * Bound to a transaction (a [transactionId]), the executor runs one query at a time and rejects a
 * query started while another is still running: a transaction must not be used from several
 * coroutines concurrently.
 */
public class VertxQueryExecutor(
    private val client: SqlClient,
    private val dialect: PersistenceDialect = PostgresDialect,
    private val listeners: List<QueryExecutionListener> = emptyList(),
    private val transactionId: String? = null,
    private val renderer: SqlRenderer = dialect.renderer,
) : QueryExecutor {

    private val running = AtomicInteger()

    override suspend fun executeAndGetAll(query: Query): List<RowContext> {
        val rows = execute(query)
        val index = ColumnIndex(rows.columnsNames(), dialect)
        return buildList { rows.forEach { add(VertxRowContext(it, index)) } }
    }

    override suspend fun executeAndGetFirst(query: Query): RowContext? = execute(query).firstRow()

    override suspend fun exists(query: Query): Boolean = execute(query).iterator().hasNext()

    override suspend fun executeAndGetCount(query: Query): Long {
        val iterator = execute(query).iterator()
        return if (iterator.hasNext()) iterator.next().getLong(0) else 0L
    }

    override suspend fun executeUpdate(query: Query): Int = execute(query).rowCount()

    override suspend fun executeReturning(query: Query): RowContext? = execute(query).firstRow()

    /** Runs a parameterless statement such as `BEGIN` or `SAVEPOINT x`, reported like any query. */
    internal suspend fun executeStatement(sql: String) {
        observed(RenderedQuery(sql, emptyList())) { client.query(sql).execute().awaitSuspending() }
    }

    private fun RowSet<Row>.firstRow(): RowContext? {
        val iterator = iterator()
        return if (iterator.hasNext()) {
            val row = iterator.next()
            VertxRowContext(row, ColumnIndex.of(row, dialect))
        } else {
            null
        }
    }

    private suspend fun execute(query: Query): RowSet<Row> {
        val exclusive = transactionId != null
        if (exclusive && running.getAndIncrement() > 0) {
            running.decrementAndGet()
            throw IllegalStateException(
                "Transaction $transactionId is used by several coroutines at once - " +
                    "run the statements of one transaction one after the other",
            )
        }

        try {
            val rendered = renderer.render(query)
            return observed(rendered) {
                client
                    .preparedQuery(rendered.sql)
                    .execute(Tuple.from(rendered.parameters.map(::bindValue)))
                    .awaitSuspending()
            }
        } finally {
            if (exclusive) running.decrementAndGet()
        }
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
                // Reported even when cancelled: the statement may well have reached the database.
                withContext(
                    NonCancellable,
                ) { report(rendered, started.elapsedNow(), rowCount = null, error = e) }

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
        if (listeners.isEmpty()) return
        val execution =
            QueryExecution(
                query = rendered,
                operation = QueryOperation.of(rendered.sql),
                duration = duration,
                rowCount = rowCount,
                transactionId = transactionId,
                error = error,
            )
        listeners.forEach { listener ->
            try {
                listener.executed(execution)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                log.warn(e) { "QueryExecutionListener ${listener.javaClass.name} failed; ignored" }
            }
        }
    }

    /**
     * jOOQ's [JSON]/[JSONB] bind values become the Vert.x JSON value the Postgres client encodes for
     * a json/jsonb parameter: a [JsonObject], a [JsonArray], a scalar, or [Tuple.JSON_NULL].
     */
    private fun bindValue(value: Any?): Any? =
        when (value) {
            is JSON -> decodeJson(value.data())
            is JSONB -> decodeJson(value.data())
            else -> value
        }

    private fun decodeJson(raw: String): Any {
        val text = raw.trim()
        return when {
            text.startsWith("{") -> JsonObject(text)
            text.startsWith("[") -> JsonArray(text)
            text == "null" -> Tuple.JSON_NULL
            else -> Json.decodeValue(text)
        }
    }
}
