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

import io.opentelemetry.sdk.testing.exporter.InMemorySpanExporter
import io.opentelemetry.sdk.trace.SdkTracerProvider
import io.opentelemetry.sdk.trace.export.SimpleSpanProcessor
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.entry
import org.assertj.core.api.SoftAssertions.assertSoftly
import org.junit.jupiter.api.Test
import java.sql.SQLException
import kotlin.time.Duration.Companion.milliseconds

/** [RequestQueryProfile] and [RequestQueryReporter]: the totals of a request and their report. */
internal class RequestQueryProfileTest {

    @Test
    fun `counts data statements, transactions, failures and the time spent in the database`() {
        // given
        val profile = RequestQueryProfile()

        // when
        listOf(
            execution(
                sql = "BEGIN",
                duration = 1.milliseconds,
                transactionId = "a"
            ),
            execution(
                sql = "SELECT 1",
                duration = 2.milliseconds,
                transactionId = "a"
            ),
            execution(
                sql = "COMMIT",
                duration = 1.milliseconds,
                transactionId = "a"
            ),
            execution(
                sql = "UPDATE t SET x = 1",
                duration = 4.milliseconds,
                error = SQLException("boom"),
            ),
        ).forEach(profile::record)

        // then
        val snapshot = profile.snapshot()
        assertSoftly { softly ->
            with(snapshot) {
                softly.assertThat(queryCount).isEqualTo(2)
                softly.assertThat(transactionCount).isEqualTo(1)
                softly.assertThat(failedCount).isEqualTo(1)
                softly.assertThat(databaseTime).isEqualTo(8.milliseconds)
            }
        }
    }

    @Test
    fun `finds the SQL texts that ran as often as the N+1 threshold`() {
        // given
        val profile = RequestQueryProfile()
        repeat(3) {
            profile.record(execution($$"SELECT * FROM player WHERE id = $1"))
        }

        profile.record(execution("SELECT * FROM match"))

        // when
        val repeated = profile.snapshot().repeatedQueries(threshold = 3)

        // then
        assertThat(repeated).containsExactly(entry($$"SELECT * FROM player WHERE id = $1", 3))
    }

    @Test
    fun `keeps a bounded number of distinct SQL texts but still counts every statement`() {
        // given
        val profile = RequestQueryProfile(maxDistinctQueries = 2)

        // when
        (1..5).forEach { profile.record(execution("SELECT $it")) }

        // then
        assertSoftly { softly ->
            with(profile.snapshot()) {
                softly.assertThat(queryCount).isEqualTo(5)
                softly.assertThat(executionsBySql).hasSize(2)
                softly.assertThat(truncated).isTrue()
            }
        }
    }

    @Test
    fun `reports the totals and a probable N+1 problem on the request span`() {
        // given
        val exporter = InMemorySpanExporter.create()
        val tracer =
            SdkTracerProvider
                .builder()
                .addSpanProcessor(SimpleSpanProcessor.create(exporter))
                .build()
                .get("test")
        val span = tracer.spanBuilder("GET /matches").startSpan()
        val profile = RequestQueryProfile()
        repeat(4) {
            profile.record(
                execution($$"SELECT * FROM player WHERE id = $1", duration = 2.milliseconds),
            )
        }

        // when
        RequestQueryReporter(nPlusOneThreshold = 3).report(profile, span, "req-1")
        span.end()

        // then
        assertSoftly { softly ->
            with(exporter.finishedSpanItems.single().attributes) {
                softly.assertThat(get(SpanAttributes.QUERY_COUNT)).isEqualTo(4L)
                softly.assertThat(get(SpanAttributes.DATABASE_TIME_MS)).isEqualTo(8.0)
                softly.assertThat(get(SpanAttributes.N_PLUS_ONE)).isTrue()
            }
        }
    }
}
