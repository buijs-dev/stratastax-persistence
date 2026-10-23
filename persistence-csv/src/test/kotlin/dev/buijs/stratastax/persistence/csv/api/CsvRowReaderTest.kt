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
package dev.buijs.stratastax.persistence.csv.api

import dev.buijs.stratastax.persistence.core.spi.RowContext
import dev.buijs.stratastax.persistence.core.spi.RowMapper
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.catchThrowable
import org.junit.jupiter.api.Test
import java.io.StringReader

/**
 * [CsvRowReader]: mapping every data row with a [RowMapper], column-name matching, and how rows
 * that cannot be mapped are reported or rejected.
 */
class CsvRowReaderTest {

    private data class RankingEntry(
        val id: Int,
        val name: String,
        val year: Int,
        val isExpansion: Boolean,
    )

    private val mapper =
        RowMapper { row: RowContext ->
            RankingEntry(
                id = row.integer("id"),
                name = row.string("name"),
                year = row.integer("yearPublished"),
                isExpansion = row.boolean("isExpansion"),
            )
        }

    private fun read(
        csv: String,
        options: CsvReadOptions = CsvReadOptions.DEFAULT,
    ) = CsvRowReader.read(StringReader(csv.trimIndent()), mapper, options)

    @Test
    fun `maps every data row in file order`() {
        // given
        val csv =
            """
            id,name,year_published,is_expansion
            13,Catan,1995,0
            822,Catan: Seafarers,1997,1
            """

        // when
        val result = read(csv)

        // then
        assertThat(result.skipped).isEmpty()
        assertThat(result.imported)
            .containsExactly(
                RankingEntry(13, "Catan", 1995, isExpansion = false),
                RankingEntry(822, "Catan: Seafarers", 1997, isExpansion = true),
            )
    }

    @Test
    fun `a header without data rows reads as an empty result`() {
        // given
        val csv = "id,name,year_published,is_expansion\n"

        // when
        val result = read(csv)

        // then
        assertThat(result.imported).isEmpty()
        assertThat(result.skipped).isEmpty()
    }

    @Test
    fun `skips a row that cannot be mapped and reports its record number and reason`() {
        // given
        val csv =
            """
            id,name,year_published,is_expansion
            13,Catan,1995,0
            NotANumber,Catan: Seafarers,1997,1
            822,Carcassonne,2000,0
            """

        // when
        val result = read(csv)

        // then
        assertThat(result.imported.map { it.id }).containsExactly(13, 822)
        assertThat(result.skipped).hasSize(1)
        assertThat(result.skipped.single().record).isEqualTo(2L)
        assertThat(result.skipped.single().reason)
            .contains("Column 'id'")
            .contains("NotANumber")
    }

    @Test
    fun `failOnInvalidRow throws for the first row that cannot be mapped`() {
        // given
        val csv =
            """
            id,name,year_published,is_expansion
            13,Catan,1995,maybe
            """

        // when
        val thrown = catchThrowable { read(csv, CsvReadOptions(failOnInvalidRow = true)) }

        // then
        assertThat(thrown)
            .isInstanceOf(CsvImportException::class.java)
            .hasMessageContaining("Record 1")
            .hasMessageContaining("isExpansion")
    }

    @Test
    fun `rejects a header with two columns that normalize to the same name`() {
        // given
        val csv =
            """
            id,name,year_published,yearpublished,is_expansion
            13,Catan,1995,1995,0
            """

        // when
        val thrown = catchThrowable { read(csv) }

        // then
        assertThat(thrown)
            .isInstanceOf(CsvImportException::class.java)
            .hasMessageContaining("normalize to 'yearpublished'")
    }

    @Test
    fun `a custom column name normalizer replaces the default matching`() {
        // given
        val csv =
            """
            ID,NAME,YEARPUBLISHED,ISEXPANSION
            13,Catan,1995,false
            """
        val options = CsvReadOptions(columnNameNormalizer = { it.uppercase() })

        // when
        val result = read(csv, options)

        // then
        assertThat(
            result.imported,
        ).containsExactly(RankingEntry(13, "Catan", 1995, isExpansion = false))
    }

    @Test
    fun `the default normalizer ignores case, underscores and surrounding spaces`() {
        // given
        val names = listOf("is_expansion", "isExpansion", " IsExpansion ")

        // when
        val normalized = names.map(::normalizeCsvColumnName)

        // then
        assertThat(normalized).containsOnly("isexpansion")
    }
}
