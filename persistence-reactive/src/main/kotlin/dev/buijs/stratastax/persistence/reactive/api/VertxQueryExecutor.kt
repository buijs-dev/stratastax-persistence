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
import io.github.oshai.kotlinlogging.KotlinLogging
import io.smallrye.mutiny.coroutines.awaitSuspending
import io.vertx.mutiny.sqlclient.Row
import io.vertx.mutiny.sqlclient.RowSet
import io.vertx.mutiny.sqlclient.SqlClient
import io.vertx.mutiny.sqlclient.Tuple
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import java.time.Clock
import java.time.Instant
import java.util.concurrent.atomic.AtomicInteger
import kotlin.coroutines.cancellation.CancellationException
import kotlin.time.Duration
import kotlin.time.TimeSource

private val log = KotlinLogging.logger {}

/**
 * A [QueryExecutor] over a Vert.x Mutiny [SqlClient] (a pool, or one connection): runs each
 * statement as a prepared query, hands its rows back as [RowContext]s and reports it to every one
 * of [listeners]. This also includes a failed statement and the transaction-control statements of a
 * transaction, tagged with [transactionId] when this executor is bound to one.
 *
 * A listener that throws is logged and otherwise ignored: it never changes the outcome of a
 * statement or a transaction.
 *
 * Bound to a transaction (a [transactionId]), the executor runs one query at a time and rejects a
 * query started while another is still running: a transaction must not be used from several
 * coroutines concurrently.
 *
 * [clock] stamps [QueryExecution.startedAt]; the duration is measured on a monotonic clock.
 */
@Suppress("SqlSourceToSinkFlow")
public class VertxQueryExecutor(
    private val client: SqlClient,
    private val dialect: PersistenceDialect,
    private val listeners: List<QueryExecutionListener> = emptyList(),
    private val transactionId: String? = null,
    private val clock: Clock = Clock.systemUTC(),
) : QueryExecutor {

    private val running = AtomicInteger()

    override suspend fun query(
        sql: String,
        params: List<Any?>,
    ): List<RowContext> = allRows(run(RenderedQuery(sql, params)))

    override suspend fun queryFirst(
        sql: String,
        params: List<Any?>,
    ): RowContext? = run(RenderedQuery(sql, params)).firstRow()

    override suspend fun execute(
        sql: String,
        params: List<Any?>,
    ): Int = run(RenderedQuery(sql, params)).rowCount()

    override suspend fun batch(
        sql: String,
        batch: List<List<Any?>>,
    ): List<List<RowContext>> {
        if (batch.isEmpty()) return emptyList()
        val first =
            exclusively {
                observed(RenderedQuery(sql, batch.first()), batch.size) {
                    client.preparedQuery(sql).executeBatch(batch.map(::tupleOf)).awaitSuspending()
                }
            }

        return resultsOf(first).take(batch.size).map(::allRows).toList()
    }

    /** Runs a parameterless statement such as `BEGIN` or `SAVEPOINT x`, reported like any query. */
    internal suspend fun executeStatement(sql: String) {
        observed(RenderedQuery(sql, emptyList())) {
            client.query(sql).execute().awaitSuspending()
        }
    }

    /** Every row of [rows]; none for a statement that returns no columns (no `RETURNING`). */
    private fun allRows(rows: RowSet<Row>): List<RowContext> {
        val columns: List<String> = rows.columnsNames() ?: return emptyList()
        val index = ColumnIndex(columns, dialect)
        return buildList { rows.forEach { add(VertxRowContext(it, index)) } }
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

    private suspend fun run(rendered: RenderedQuery): RowSet<Row> =
        exclusively {
            observed(rendered) {
                client
                    .preparedQuery(rendered.sql)
                    .execute(tupleOf(rendered.parameters))
                    .awaitSuspending()
            }
        }

    /**
     * Runs [block] as the only statement of this executor's transaction (if it is bound to one).
     *
     * @throws IllegalStateException when another statement of the transaction is still running.
     */
    private suspend fun <T> exclusively(block: suspend () -> T): T {
        val exclusive = transactionId != null
        if (exclusive && running.getAndIncrement() > 0) {
            running.decrementAndGet()
            throw IllegalStateException(
                "Transaction $transactionId is used by several coroutines at once - " +
                    "run the statements of one transaction one after the other",
            )
        }

        try {
            return block()
        } finally {
            if (exclusive) {
                running.decrementAndGet()
            }
        }
    }

    private suspend fun observed(
        rendered: RenderedQuery,
        batchSize: Int = 1,
        run: suspend () -> RowSet<Row>,
    ): RowSet<Row> {
        val startedAt = clock.instant()
        val started = TimeSource.Monotonic.markNow()
        val rows =
            try {
                run()
            } catch (e: Throwable) {
                // Reported even when canceled: the statement may well have reached the database.
                withContext(
                    NonCancellable,
                ) {
                    report(
                        rendered,
                        startedAt,
                        started.elapsedNow(),
                        rowCount = null,
                        error = e,
                        batchSize,
                    )
                }

                throw e
            }

        val rowCount = resultsOf(rows).take(batchSize).sumOf { it.rowCount() }

        report(rendered, startedAt, started.elapsedNow(), rowCount, error = null, batchSize)
        return rows
    }

    private suspend fun report(
        rendered: RenderedQuery,
        startedAt: Instant,
        duration: Duration,
        rowCount: Int?,
        error: Throwable?,
        batchSize: Int,
    ) {
        if (listeners.isEmpty()) return
        val execution =
            QueryExecution(
                query = rendered,
                operation = QueryOperation.of(rendered.sql),
                duration = duration,
                startedAt = startedAt,
                rowCount = rowCount,
                transactionId = transactionId,
                error = error,
                batchSize = batchSize,
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

    /** The parameters as [dialect] binds them, see [PersistenceDialect.bindValue]. */
    private fun tupleOf(parameters: List<Any?>): Tuple =
        Tuple.from(parameters.map(dialect::bindValue))
}

/**
 * [first] and the results that follow it: the result of every parameter set of a batch, in order.
 * A plain statement has only [first].
 */
private fun resultsOf(first: RowSet<Row>): Sequence<RowSet<Row>> =
    generateSequence(first) { it.next() }
