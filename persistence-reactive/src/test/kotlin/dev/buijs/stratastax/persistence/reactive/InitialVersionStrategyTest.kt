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

import dev.buijs.stratastax.persistence.reactive.app.InitialVersionStrategy
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * `InitialVersionStrategy` has no jOOQ-side consumer within this module itself (unlike
 * [VersionIncrementStrategy], which [JooqUpdateAccess] actually calls) - it's implemented by
 * generated `${Entity}CreateMapper` dependents and the library-shipped
 * `DefaultInitialVersionStrategy` (`quarkus-starter`), so this only confirms the `fun interface`
 * shape - a lambda works as expected, and a non-default starting value is respected.
 */
internal class InitialVersionStrategyTest {

    @Test
    fun `a lambda-backed strategy returns whatever it provides`() {
        val strategy = InitialVersionStrategy { 1L }

        assertThat(strategy.initial()).isEqualTo(1L)
    }

    @Test
    fun `a custom starting value is respected`() {
        val strategy = InitialVersionStrategy { 100L }

        assertThat(strategy.initial()).isEqualTo(100L)
    }
}
