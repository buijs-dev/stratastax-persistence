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
package dev.buijs.stratastax.persistence.postgres.test.api

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/** The conversion of native `$n` placeholders to the `?` JDBC takes, see [PostgresQueryPlanAnalyzer]. */
class JdbcPlaceholdersTest {

    @Test
    fun `converts native placeholders to JDBC ones outside literals`() {
        // given
        val sql = "select '\$1', \"\$2\" from t where a = \$1 and b in (\$2, \$10)"

        // when
        val converted = toJdbcPlaceholders(sql)

        // then
        assertThat(converted).isEqualTo("select '\$1', \"\$2\" from t where a = ? and b in (?, ?)")
    }
}
