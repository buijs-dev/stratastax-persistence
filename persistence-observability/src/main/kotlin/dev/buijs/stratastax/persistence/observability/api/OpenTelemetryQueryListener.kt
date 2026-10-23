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
package dev.buijs.stratastax.persistence.observability.api

import dev.buijs.stratastax.persistence.core.spi.QueryExecution
import dev.buijs.stratastax.persistence.core.spi.QueryExecutionListener
import dev.buijs.stratastax.persistence.core.spi.QueryOperation
import dev.buijs.stratastax.persistence.observability.api.SpanAttributes.BATCH_SIZE
import dev.buijs.stratastax.persistence.observability.api.SpanAttributes.DB_OPERATION
import dev.buijs.stratastax.persistence.observability.api.SpanAttributes.DB_QUERY_TEXT
import dev.buijs.stratastax.persistence.observability.api.SpanAttributes.DB_RETURNED_ROWS
import dev.buijs.stratastax.persistence.observability.api.SpanAttributes.DB_SYSTEM
import dev.buijs.stratastax.persistence.observability.api.SpanAttributes.ERROR_TYPE
import dev.buijs.stratastax.persistence.observability.api.SpanAttributes.REQUEST_ID
import dev.buijs.stratastax.persistence.observability.api.SpanAttributes.TRANSACTION_ID
import dev.buijs.stratastax.persistence.observability.api.SpanAttributes.TRANSACTION_OUTCOME
import io.opentelemetry.api.common.Attributes
import io.opentelemetry.api.trace.Span
import io.opentelemetry.api.trace.SpanKind
import io.opentelemetry.api.trace.StatusCode
import io.opentelemetry.api.trace.Tracer
import io.opentelemetry.context.Context
import kotlinx.coroutines.currentCoroutineContext
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap
import kotlin.time.toJavaDuration

/**
 * How [OpenTelemetryQueryListener] turns statements into spans.
 *
 * @property dbSystem the `db.system.name` of every span, e.g. `postgresql`: the core does not know
 *   which dialect runs.
 * @property captureQueryText whether to set `db.query.text`. The SQL carries placeholders, never the
 *   bound values.
 * @property parameterRenderer when set, every bind value becomes a `db.query.parameter.<index>`
 *   attribute, rendered by it - so it decides what to redact. Off by default: values are often
 *   personal data.
 * @property traceUncorrelated whether a statement without a [QueryCorrelation] in its coroutine
 *   context (a background job) is traced under OpenTelemetry's current context instead of skipped.
 * @property maxOpenTransactions how many transactions may have an open span at once; a transaction
 *   started beyond it gets no span of its own and its statements hang under the request.
 */
public data class QueryTracingOptions(
    val dbSystem: String,
    val captureQueryText: Boolean = true,
    val parameterRenderer: ((Any?) -> String)? = null,
    val traceUncorrelated: Boolean = false,
    val maxOpenTransactions: Int = 10_000,
)

/**
 * A [QueryExecutionListener] that puts every statement on the timeline of the request that caused
 * it, as OpenTelemetry spans:
 *
 * ```
 * HTTP GET /matches/{id}         (the request's server span, the parent in QueryCorrelation)
 *   transaction                  (BEGIN start .. COMMIT/ROLLBACK end; savepoints are events)
 *     SELECT                     (one CLIENT span per statement, at its exact start and end)
 *   SELECT                       (a statement outside a transaction)
 * ```
 *
 * The request comes from the [QueryCorrelation] in the coroutine context the statement ran in;
 * without one the statement is skipped (see [QueryTracingOptions.traceUncorrelated]). Spans get
 * the statement's own timestamps ([QueryExecution.startedAt] and its duration), so the timeline is
 * exact even though a statement is only reported once it completed. Every statement is also
 * recorded into the [RequestQueryProfile] of the context, if there is one.
 *
 * A transaction's span stays open from its `BEGIN` until its `COMMIT` or `ROLLBACK`, which the
 * transactional executor always runs and reports; only open spans are held, never statements.
 */
public class OpenTelemetryQueryListener(
    private val tracer: Tracer,
    private val options: QueryTracingOptions,
) : QueryExecutionListener {

    private val openTransactions = ConcurrentHashMap<String, Span>()

    override suspend fun executed(execution: QueryExecution) {
        val context = currentCoroutineContext()
        context[RequestQueryProfile]?.record(execution)
        val correlation = context[QueryCorrelation]
        val parent =
            correlation?.parent
                ?: if (options.traceUncorrelated) {
                    Context.current()
                } else {
                    return
                }

        val transactionId = execution.transactionId
        val operation = execution.operation
        when {
            transactionId == null -> {
                statement(parent, correlation, execution)
            }

            operation == QueryOperation.BEGIN -> {
                begin(parent, correlation, transactionId, execution)
            }

            operation == QueryOperation.COMMIT || operation == QueryOperation.ROLLBACK -> {
                end(transactionId, execution)
            }

            operation.isTransactionControl -> {
                savepoint(transactionId, execution)
            }

            else -> {
                val transaction = openTransactions[transactionId]
                statement(transaction?.let(parent::with) ?: parent, correlation, execution)
            }
        }
    }

    private fun statement(
        parent: Context,
        correlation: QueryCorrelation?,
        execution: QueryExecution,
    ) {
        val operation = execution.operation
        val name =
            if (operation == QueryOperation.UNKNOWN) {
                options.dbSystem
            } else {
                operation.name
            }

        val builder =
            tracer
                .spanBuilder(name)
                .setSpanKind(SpanKind.CLIENT)
                .setParent(parent)
                .setStartTimestamp(execution.startedAt)
                .setAttribute(DB_SYSTEM, options.dbSystem)
                .setAttribute(DB_OPERATION, operation.name)

        if (options.captureQueryText) {
            builder.setAttribute(DB_QUERY_TEXT, execution.query.sql)
        }

        options.parameterRenderer?.let { render ->
            execution.query.parameters.forEachIndexed { index, value ->
                builder.setAttribute(SpanAttributes.parameter(index), render(value))
            }
        }

        execution.rowCount?.let {
            builder.setAttribute(DB_RETURNED_ROWS, it.toLong())
        }

        execution.transactionId?.let {
            builder.setAttribute(TRANSACTION_ID, it)
        }

        correlation?.let {
            builder.setAttribute(REQUEST_ID, it.requestId)
        }

        if (execution.batchSize > 1) {
            builder.setAttribute(BATCH_SIZE, execution.batchSize.toLong())
        }

        val span = builder.startSpan()
        recordFailure(span, execution)
        span.end(execution.endedAt())
    }

    private fun begin(
        parent: Context,
        correlation: QueryCorrelation?,
        transactionId: String,
        execution: QueryExecution,
    ) {
        if (openTransactions.size >= options.maxOpenTransactions) {
            return
        }

        val builder =
            tracer
                .spanBuilder("transaction")
                .setSpanKind(SpanKind.INTERNAL)
                .setParent(parent)
                .setStartTimestamp(execution.startedAt)
                .setAttribute(DB_SYSTEM, options.dbSystem)
                .setAttribute(TRANSACTION_ID, transactionId)

        correlation?.let {
            builder.setAttribute(REQUEST_ID, it.requestId)
        }

        val span = builder.startSpan()
        recordFailure(span, execution)
        openTransactions[transactionId] = span
    }

    private fun end(
        transactionId: String,
        execution: QueryExecution,
    ) {
        val span =
            openTransactions.remove(transactionId)
                ?: return

        span.setAttribute(TRANSACTION_OUTCOME, execution.operation.name.lowercase())
        recordFailure(span, execution)
        span.end(execution.endedAt())
    }

    private fun savepoint(
        transactionId: String,
        execution: QueryExecution,
    ) {
        val span =
            openTransactions[transactionId]
                ?: return

        val attributes =
            if (options.captureQueryText) {
                Attributes.of(DB_QUERY_TEXT, execution.query.sql)
            } else {
                Attributes.empty()
            }

        span.addEvent(execution.operation.name.lowercase(), attributes, execution.startedAt)
        recordFailure(span, execution)
    }

    private fun recordFailure(
        span: Span,
        execution: QueryExecution,
    ) {
        val error =
            execution.error
                ?: return

        span.recordException(error)
        span.setAttribute(ERROR_TYPE, error.javaClass.name)
        span.setStatus(StatusCode.ERROR)
    }

    private fun QueryExecution.endedAt(): Instant = startedAt.plus(duration.toJavaDuration())
}
