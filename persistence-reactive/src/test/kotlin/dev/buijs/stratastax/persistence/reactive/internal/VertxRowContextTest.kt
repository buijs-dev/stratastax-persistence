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
package dev.buijs.stratastax.persistence.reactive.internal

import dev.buijs.stratastax.persistence.core.spi.UnknownColumnException
import dev.buijs.stratastax.persistence.reactive.testfixtures.AnsiTestDialect
import io.mockk.every
import io.mockk.mockk
import io.vertx.core.json.JsonArray
import io.vertx.core.json.JsonObject
import io.vertx.mutiny.sqlclient.Row
import io.vertx.sqlclient.data.Numeric
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.catchThrowable
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.LocalDate
import java.util.UUID

/**
 * [VertxRowContext] and [ColumnIndex] over a mocked Vert.x [Row]: name matching, the typed
 * accessors, JSON decoding and the errors for unknown columns and wrong types.
 */
class VertxRowContextTest {

    /** A row with these columns (named as Postgres returns them), read by position. */
    @Suppress("UNCHECKED_CAST")
    private fun rowOf(vararg columns: Pair<String, Any?>): VertxRowContext {
        val values = columns.map { it.second }

        fun <T> at(position: Int): T? = values[position] as T?
        val row = mockk<Row>()
        every { row.size() } returns columns.size
        every { row.getColumnName(any()) } answers { columns[firstArg<Int>()].first }

        every { row.getUUID(any<Int>()) } answers { at(firstArg()) }

        every { row.getString(any<Int>()) } answers { at(firstArg()) }

        every { row.getLong(any<Int>()) } answers { at(firstArg()) }

        every { row.getLocalDate(any<Int>()) } answers { at(firstArg()) }

        every { row.getNumeric(any<Int>()) } answers { at(firstArg()) }

        every { row.getJsonArray(any<Int>()) } answers { at(firstArg()) }

        every { row.getJsonObject(any<Int>()) } answers { at(firstArg()) }

        every { row.getArrayOfStrings(any<Int>()) } answers { at(firstArg()) }

        every { row.getArrayOfNumerics(any<Int>()) } answers { at(firstArg()) }

        every { row.getValue(any<Int>()) } answers { at(firstArg()) }

        return VertxRowContext(row, ColumnIndex.of(row, AnsiTestDialect))
    }

    @Test
    fun `matches a column exactly, or case-insensitively as Postgres folds unquoted names`() {
        // given
        val row = rowOf("createdAt" to "quoted", "gamename" to "folded")

        // when
        val exact = row.string("createdAt")
        val folded = row.string("gameName")

        // then
        assertThat(exact).isEqualTo("quoted")
        assertThat(folded).isEqualTo("folded")
    }

    @Test
    fun `reading a column the row does not have throws`() {
        // given
        val row = rowOf("id" to 1L)

        // when
        val thrown = catchThrowable { row.stringOrNull("name") }

        // then
        assertThat(thrown)
            .isInstanceOfSatisfying(UnknownColumnException::class.java) {
                assertThat(it.available).containsExactly("id")
            }
    }

    @Test
    fun `reads scalar columns`() {
        // given
        val id = UUID.randomUUID()
        val row =
            rowOf(
                "id" to id,
                "score" to 42L,
                "day" to LocalDate.of(2026, 1, 2),
                "price" to Numeric.create(BigDecimal("9.5")),
            )

        // when
        val values =
            listOf(
                row.uuid("id"),
                row.long("score"),
                row.localDate("day"),
                row.decimal("price"),
                row.rawOrNull("score"),
            )

        // then
        assertThat(
            values,
        ).containsExactly(id, 42L, LocalDate.of(2026, 1, 2), BigDecimal("9.5"), 42L)
    }

    @Test
    fun `reads array columns, empty when NULL, and rejects a NULL element`() {
        // given
        val row =
            rowOf(
                "tags" to arrayOf("a", "b"),
                "none" to null,
                "holes" to arrayOf("a", null),
                "prices" to arrayOf(Numeric.create(1)),
            )

        // when
        val tags = row.stringList("tags")
        val none = row.stringList("none")
        val prices = row.decimalList("prices")
        val holes = catchThrowable { row.stringList("holes") }

        // then
        assertThat(tags).containsExactly("a", "b")
        assertThat(none).isEmpty()
        assertThat(prices).containsExactly(BigDecimal.ONE)
        assertThat(holes).hasMessageContaining("contains a null element")
    }

    @Test
    fun `decodes JSON columns to plain maps and lists, nested ones included`() {
        // given
        val array =
            JsonArray().add(
                JsonObject()
                    .put(
                        "name",
                        "a",
                    ).put("tags", JsonArray().add("x"))
                    .put("meta", JsonObject().put("k", 1)),
            )
        val row = rowOf("items" to array, "attributes" to JsonObject().put("players", 4))

        // when
        val items = row.objectList("items")
        val attributes = row.map("attributes")

        // then
        assertThat(items.single().string("name")).isEqualTo("a")
        assertThat(items.single().stringList("tags")).containsExactly("x")
        assertThat(items.single().map("meta")).isEqualTo(mapOf("k" to 1))
        assertThat(attributes).isEqualTo(mapOf("players" to 4))
    }

    @Test
    fun `a value of another type throws naming the column`() {
        // given
        val row = mockk<Row>()
        every { row.size() } returns 1
        every { row.getColumnName(0) } returns "name"
        every { row.getLong(0) } throws ClassCastException("String cannot be cast to Long")
        val context = VertxRowContext(row, ColumnIndex.of(row, AnsiTestDialect))

        // when
        val thrown = catchThrowable { context.longOrNull("name") }

        // then
        assertThat(thrown)
            .isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining("Column 'name'")
    }
}
