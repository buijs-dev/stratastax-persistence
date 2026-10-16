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
package dev.buijs.stratastax.persistence.csv

import dev.buijs.stratastax.persistence.csv.api.CsvRowReader
import dev.buijs.stratastax.persistence.csv.spi.CsvRowMapper
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.io.StringReader

class CsvRowReaderTest {

    @Test
    fun `reads every data row into a mapped list, header excluded`() {
        val csv =
            """
            id,name,yearpublished,rank,is_expansion
            13,Catan,1995,1,0
            822,Catan Expansion,1997,2,1
            """.trimIndent()

        val entries = CsvRowReader.read(StringReader(csv), mapper)
        assertThat(entries.skipped).isEmpty()
        assertThat(entries.imported)
            .containsExactly(
                BggRankingEntry(13, "Catan", 1995, 1, isExpansion = false),
                BggRankingEntry(822, "Catan Expansion", 1997, 2, isExpansion = true),
            )
    }

    @Test
    fun `an empty data set (header only) reads as an empty list`() {
        val csv = "id,name,yearpublished,rank,is_expansion\n"

        val entries = CsvRowReader.read(StringReader(csv), mapper)
        assertThat(entries.skipped).isEmpty()
        assertThat(entries.imported).isEmpty()
    }

    @Test
    fun `skips corrupt rows`() {
        val csv =
            """
            id,name,yearpublished,rank,is_expansion
            13,Catan,1995,1,0
            NotAnIdea,Catan Expansion,1997,2,1
            """.trimIndent()

        val entries = CsvRowReader.read(StringReader(csv), mapper)
        assertThat(entries.skipped).containsExactly(1)
        assertThat(entries.imported)
            .containsExactly(BggRankingEntry(13, "Catan", 1995, 1, isExpansion = false))
    }
}

private data class BggRankingEntry(
    val id: Long,
    val name: String,
    val yearPublished: Int,
    val rank: Int,
    val isExpansion: Boolean,
)

private val mapper =
    CsvRowMapper { row ->
        BggRankingEntry(
            id = row.long("id"),
            name = row.string("name"),
            yearPublished = row.integer("yearpublished"),
            rank = row.integer("rank"),
            isExpansion = row.boolean("is_expansion"),
        )
    }
