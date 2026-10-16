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
package dev.buijs.stratastax.persistence.reactive

import dev.buijs.stratastax.persistence.reactive.app.ColumnIndex
import dev.buijs.stratastax.persistence.reactive.app.VertxRowContext
import io.mockk.every
import io.mockk.mockk
import io.vertx.core.json.JsonArray
import io.vertx.core.json.JsonObject
import io.vertx.mutiny.sqlclient.Row
import io.vertx.sqlclient.data.Numeric
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.OffsetDateTime
import java.util.UUID

/**
 * No-database unit tests for the [VertxRowContext] adapter - mocks [io.vertx.mutiny.sqlclient.Row]
 * directly rather than needing a live Postgres/Vert.x connection (that's what the
 * Testcontainers-based `VertxQueryExecutorIntegrationTest` is for).
 */
internal class VertxRowContextTest {

    /** A row with these (lowercase, as Postgres returns them) columns, read by position. */
    private fun rowOf(vararg columns: Pair<String, Any?>): Row {
        val values = columns.map { it.second }

        fun <T> at(position: Int): T? = values[position] as T?
        val row = mockk<Row>()
        every { row.size() } returns columns.size
        every { row.getColumnName(any()) } answers { columns[firstArg<Int>()].first }

        every { row.getUUID(any<Int>()) } answers { at(firstArg()) }

        every { row.getString(any<Int>()) } answers { at(firstArg()) }

        every { row.getLong(any<Int>()) } answers { at(firstArg()) }

        every { row.getNumeric(any<Int>()) } answers { at(firstArg()) }

        every { row.getJsonArray(any<Int>()) } answers { at(firstArg()) }

        every { row.getJsonObject(any<Int>()) } answers { at(firstArg()) }

        every { row.getArrayOfStrings(any<Int>()) } answers { at(firstArg()) }

        every { row.getOffsetDateTime(any<Int>()) } answers { at(firstArg()) }

        every { row.getValue(any<Int>()) } answers { at(firstArg()) }

        return row
    }

    @Test
    fun `reads scalar columns, lowercasing the name first`() {
        val id = UUID.randomUUID()
        val row = rowOf("id" to id, "name" to "Catan", "score" to 42L)

        val context = VertxRowContext(row)

        assertThat(context.uuid("ID")).isEqualTo(id)
        assertThat(context.string("NAME")).isEqualTo("Catan")
        assertThat(context.long("SCORE")).isEqualTo(42L)
    }

    @Test
    fun `a null value returns null via the -OrNull accessor`() {
        val context = VertxRowContext(rowOf("name" to null))

        assertThat(context.stringOrNull("name")).isNull()
    }

    @Test
    fun `a column the result set lacks reads as null`() {
        val context = VertxRowContext(rowOf("id" to UUID.randomUUID()))

        assertThat(context.stringOrNull("name")).isNull()
        assertThat(context.rawOrNull("name")).isNull()
    }

    @Test
    fun `rows of one result set share one column index`() {
        val index = ColumnIndex(listOf("id", "name"))
        val first = VertxRowContext(rowOf("id" to UUID.randomUUID(), "name" to "Catan"), index)
        val second = VertxRowContext(rowOf("id" to UUID.randomUUID(), "name" to "Azul"), index)

        assertThat(first.string("NAME")).isEqualTo("Catan")
        assertThat(second.string("NAME")).isEqualTo("Azul")
        assertThat(index.of("NAME")).isEqualTo(1)
        assertThat(index.of("missing")).isEqualTo(-1)
    }

    @Test
    fun `decimal reads through Vert-x's own Numeric wrapper`() {
        val expected = BigDecimal("12.50")
        val numeric = mockk<Numeric>()
        every { numeric.bigDecimalValue() } returns expected

        val context = VertxRowContext(rowOf("price" to numeric))

        assertThat(context.decimal("price")).isEqualTo(expected)
    }

    @Test
    fun `objectListOrNull decodes a json array into plain maps`() {
        val jsonArray =
            JsonArray().add(JsonObject().put("name", "Alice")).add(JsonObject().put("name", "Bob"))

        val context = VertxRowContext(rowOf("participants" to jsonArray))
        val nested = context.objectList("participants")

        assertThat(nested.size).isEqualTo(2)
        assertThat(nested[0].string("name")).isEqualTo("Alice")
        assertThat(nested[1].string("name")).isEqualTo("Bob")
    }

    @Test
    fun `objectListOrNull is null when the column itself is absent`() {
        val context = VertxRowContext(rowOf("participants" to null))

        assertThat(context.objectList("participants")).isEqualTo(emptyList<Any>())
    }

    @Test
    fun `mapOrNull decodes a json object column into a plain map`() {
        val jsonObject = JsonObject().put("color", "red").put("level", 3)

        val context = VertxRowContext(rowOf("attributes" to jsonObject))
        val attributes = context.map("attributes")

        assertThat(attributes["color"]).isEqualTo("red")
        assertThat(attributes["level"]).isEqualTo(3)
    }

    @Test
    fun `mapOrNull is null when the column itself is absent`() {
        val context = VertxRowContext(rowOf("attributes" to null))

        assertThat(context.mapOrNull("attributes")).isNull()
    }

    @Test
    fun `array columns filter out nulls`() {
        val context = VertxRowContext(rowOf("tags" to arrayOf("a", null, "b")))

        assertThat(context.stringList("tags")).isEqualTo(listOf("a", "b"))
    }

    @Test
    fun `offsetDateTime reads through Vert-x own accessor`() {
        val now = OffsetDateTime.now()

        val context = VertxRowContext(rowOf("createdat" to now))

        assertThat(context.offsetDateTime("createdAt")).isEqualTo(now)
    }

    @Test
    fun `rawOrNull reads back through Vert-x own untyped getValue, lowercasing the name first`() {
        val context = VertxRowContext(rowOf("__junctionroot_id" to 42L))

        assertThat(context.rawOrNull("__junctionRoot_id")).isEqualTo(42L)
    }

    @Test
    fun `rawOrNull returns null for a null value`() {
        val context = VertxRowContext(rowOf("__junctionroot_id" to null))

        assertThat(context.rawOrNull("__junctionRoot_id")).isNull()
    }
}
