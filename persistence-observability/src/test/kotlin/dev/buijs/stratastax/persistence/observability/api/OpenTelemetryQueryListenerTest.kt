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
import dev.buijs.stratastax.persistence.observability.api.SpanAttributes.DB_OPERATION
import dev.buijs.stratastax.persistence.observability.api.SpanAttributes.DB_QUERY_TEXT
import dev.buijs.stratastax.persistence.observability.api.SpanAttributes.DB_RETURNED_ROWS
import dev.buijs.stratastax.persistence.observability.api.SpanAttributes.DB_SYSTEM
import dev.buijs.stratastax.persistence.observability.api.SpanAttributes.REQUEST_ID
import dev.buijs.stratastax.persistence.observability.api.SpanAttributes.TRANSACTION_OUTCOME
import dev.buijs.stratastax.persistence.observability.api.SpanAttributes.parameter
import io.opentelemetry.api.common.AttributeKey
import io.opentelemetry.api.trace.SpanKind
import io.opentelemetry.api.trace.StatusCode
import io.opentelemetry.context.Context
import io.opentelemetry.sdk.testing.exporter.InMemorySpanExporter
import io.opentelemetry.sdk.trace.SdkTracerProvider
import io.opentelemetry.sdk.trace.data.SpanData
import io.opentelemetry.sdk.trace.export.SimpleSpanProcessor
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.SoftAssertions.assertSoftly
import org.junit.jupiter.api.Test
import java.sql.SQLException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.time.Duration.Companion.milliseconds

internal class OpenTelemetryQueryListenerTest {
    private val exporter = InMemorySpanExporter.create()
    private val tracer = buildTracer()
    private val request = buildRequest()
    private val correlation = buildCorrelation()

    @Test
    fun `a statement outside a transaction is a client span under the request at its own times`() {
        // given
        val sql = $$"SELECT * FROM match WHERE id = $1"
        val select =
            execution(
                sql = sql,
                startMillis = 5,
                duration = 3.milliseconds,
                parameters = listOf(42),
            )

        // when
        listener().report(select)

        // then
        val span = spans().single()
        assertSoftly { softly ->
            with(span) {
                softly.assertThat(name).isEqualTo("SELECT")
                softly.assertThat(kind).isEqualTo(SpanKind.CLIENT)
                softly.assertThat(traceId).isEqualTo(request.spanContext.traceId)
                softly.assertThat(parentSpanId).isEqualTo(request.spanContext.spanId)
                softly.assertThat(start()).isEqualTo(5)
                softly.assertThat(end()).isEqualTo(8)
                softly.assertThat(attribute(DB_SYSTEM)).isEqualTo("postgresql")
                softly.assertThat(attribute(DB_OPERATION)).isEqualTo("SELECT")
                softly.assertThat(attribute(DB_QUERY_TEXT)).isEqualTo(sql)
                softly.assertThat(attribute(DB_RETURNED_ROWS)).isEqualTo(1L)
                softly.assertThat(attribute(REQUEST_ID)).isEqualTo("req-1")
                softly.assertThat(attribute(parameter(0))).isNull()
            }
        }
    }

    @Test
    fun `a transaction is one span from BEGIN to COMMIT with its statements as children`() {
        // given
        val statements =
            arrayOf(
                execution(
                    sql = "BEGIN",
                    startMillis = 0,
                    transactionId = "tx"
                ),
                execution(
                    sql = "SELECT 1",
                    startMillis = 2,
                    transactionId = "tx"
                ),
                execution(
                    sql = "SAVEPOINT sp_1",
                    startMillis = 4,
                    transactionId = "tx"
                ),
                execution(
                    sql = $$"INSERT INTO t VALUES ($1)",
                    startMillis = 6,
                    transactionId = "tx",
                    batchSize = 3,
                ),
                execution(
                    sql = "COMMIT",
                    startMillis = 9,
                    duration = 2.milliseconds,
                    transactionId = "tx",
                ),
            )

        // when
        listener().report(*statements)

        // then
        val transaction = spans().single { it.name == "transaction" }
        val children = spans().filter { it.parentSpanId == transaction.spanId }
        assertSoftly { softly ->
            with(transaction) {
                softly.assertThat(parentSpanId).isEqualTo(request.spanContext.spanId)
                softly.assertThat(start()).isEqualTo(0)
                softly.assertThat(end()).isEqualTo(11)
                softly.assertThat(attribute(TRANSACTION_OUTCOME)).isEqualTo("commit")
                softly.assertThat(events.map { it.name }).containsExactly("savepoint")
            }
        }

        assertSoftly { softly ->
            with(children) {
                softly.assertThat(map { it.name }).containsExactly("SELECT", "INSERT")
                softly.assertThat(map { it.attribute(SpanAttributes.TRANSACTION_ID) }).containsOnly("tx")
                softly.assertThat(last().attribute(SpanAttributes.BATCH_SIZE)).isEqualTo(3L)
            }
        }
    }

    @Test
    fun `a failed statement and its rolled back transaction are marked as errors`() {
        // given
        val failure = SQLException("duplicate key")
        val statements =
            arrayOf(
                execution(
                    sql = "BEGIN",
                    transactionId = "tx"
                ),
                execution(
                    sql = "INSERT INTO t VALUES (1)",
                    startMillis = 1,
                    transactionId = "tx",
                    error = failure,
                ),
                execution(
                    sql = "ROLLBACK",
                    startMillis = 2,
                    transactionId = "tx"
                ),
            )

        // when
        listener().report(*statements)

        // then
        val insert = spans().single { it.name == "INSERT" }
        val transaction = spans().single { it.name == "transaction" }
        assertThat(transaction.attribute(TRANSACTION_OUTCOME)).isEqualTo("rollback")
        assertSoftly { softly ->
            with(insert) {
                softly.assertThat(status.statusCode).isEqualTo(StatusCode.ERROR)
                softly.assertThat(attribute(SpanAttributes.ERROR_TYPE)).isEqualTo("java.sql.SQLException")
                softly.assertThat(events.map { it.name }).contains("exception")            }
        }
    }

    @Test
    fun `a statement without a correlation is skipped unless uncorrelated statements are traced`() {
        // given
        val select = execution("SELECT 1")
        val tracing = listener(
            options = QueryTracingOptions(
                dbSystem = "postgresql",
                traceUncorrelated = true
            )
        )

        // when
        listener().report(select, context = EmptyCoroutineContext)
        val skipped = spans().size
        tracing.report(select, context = EmptyCoroutineContext)

        // then
        assertThat(skipped).isZero()
        assertThat(spans()).hasSize(1)
    }

    @Test
    fun `bind values are only captured through a parameter renderer, which can redact them`() {
        // given
        val options =
            QueryTracingOptions(
                dbSystem = "postgresql",
                parameterRenderer = {
                    if (it is String) {
                        "?"
                    } else {
                        it.toString()
                    }
            })

        val select =
            execution(
                sql = $$"SELECT * FROM app_user WHERE email = $1 AND id = $2",
                parameters = listOf("ada@example.com", 7),
            )

        // when
        listener(options).report(select)

        // then
        val span = spans().single()
        assertThat(span.attribute(parameter(0))).isEqualTo("?")
        assertThat(span.attribute(parameter(1))).isEqualTo("7")
    }

    @Test
    fun `the query text is left out when it should not be captured`() {
        // given
        val options = QueryTracingOptions(dbSystem = "postgresql", captureQueryText = false)

        // when
        listener(options).report(execution("SELECT 1"))

        // then
        assertThat(spans().single().attribute(DB_QUERY_TEXT)).isNull()
    }

    @Test
    fun `statements of a transaction beyond the open-transaction limit hang under the request`() {
        // given
        val options = QueryTracingOptions(dbSystem = "postgresql", maxOpenTransactions = 0)
        val statements =
            arrayOf(
                execution("BEGIN", transactionId = "tx"),
                execution("SELECT 1", startMillis = 1, transactionId = "tx"),
                execution("COMMIT", startMillis = 2, transactionId = "tx"),
            )

        // when
        listener(options).report(*statements)

        // then
        val span = spans().single()
        assertThat(span.name).isEqualTo("SELECT")
        assertThat(span.parentSpanId).isEqualTo(request.spanContext.spanId)
    }

    @Test
    fun `every statement is recorded into the request's profile`() {
        // given
        val profile = RequestQueryProfile()
        val statements =
            arrayOf(
                execution("BEGIN", transactionId = "tx"),
                execution("SELECT 1", startMillis = 1, transactionId = "tx"),
                execution("COMMIT", startMillis = 2, transactionId = "tx"),
            )

        // when
        listener().report(*statements, context = correlation + profile)

        // then
        assertThat(profile.snapshot().queryCount).isEqualTo(1)
        assertThat(profile.snapshot().transactionCount).isEqualTo(1)
    }

    private fun buildTracer() =
        SdkTracerProvider
            .builder()
            .addSpanProcessor(SimpleSpanProcessor.create(exporter))
            .build()
            .get("test")

    private fun buildRequest() =
        tracer
            .spanBuilder("GET /matches/{id}")
            .setSpanKind(SpanKind.SERVER)
            .startSpan()

    private fun buildCorrelation() =
        QueryCorrelation("req-1", Context.root().with(request), "GET", "/matches/{id}")

    private fun listener(
        options: QueryTracingOptions =
            QueryTracingOptions(dbSystem = "postgresql"),
    ) = OpenTelemetryQueryListener(tracer, options)

    private fun OpenTelemetryQueryListener.report(
        vararg executions: QueryExecution,
        context: CoroutineContext = correlation,
    ) = runBlocking(context) { executions.forEach { executed(it) } }

    private fun spans(): List<SpanData> = exporter.finishedSpanItems

    private fun SpanData.start() =
        TimeUnit.NANOSECONDS.toMillis(startEpochNanos) - T0.toEpochMilli()

    private fun SpanData.end() = TimeUnit.NANOSECONDS.toMillis(endEpochNanos) - T0.toEpochMilli()

    private fun <T : Any> SpanData.attribute(key: AttributeKey<T>): T? = attributes.get(key)

}
