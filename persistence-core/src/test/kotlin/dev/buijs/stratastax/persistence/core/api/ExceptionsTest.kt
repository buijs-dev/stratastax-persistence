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
package dev.buijs.stratastax.persistence.core.api

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/** The persistence exceptions: what they carry for a caller to act on, and their messages. */
class ExceptionsTest {

    @Test
    fun `EntityNotFoundException exposes the entity and id`() {
        // given
        val id = 42L

        // when
        val exception = EntityNotFoundException("playset", id)

        // then
        assertThat(exception.entityName).isEqualTo("playset")
        assertThat(exception.id).isEqualTo(42L)
        assertThat(exception).hasMessage("No row in 'playset' where id = 42")
        assertThat(exception).isInstanceOf(IllegalArgumentException::class.java)
    }

    @Test
    fun `OptimisticLockException exposes the entity, id and stale version`() {
        // given
        val expectedVersion = 3L

        // when
        val exception = OptimisticLockException("playset", 42L, expectedVersion)

        // then
        assertThat(exception.entityName).isEqualTo("playset")
        assertThat(exception.id).isEqualTo(42L)
        assertThat(exception.expectedVersion).isEqualTo(3L)
        assertThat(exception.message).contains("playset(42)").contains("Expected version 3")
        assertThat(exception).isInstanceOf(IllegalStateException::class.java)
    }

    @Test
    fun `ReferencedEntityNotFoundException exposes the missing reference`() {
        // given
        val value = "game-1"

        // when
        val exception = ReferencedEntityNotFoundException("game", "public_id", value)

        // then
        assertThat(exception.tableName).isEqualTo("game")
        assertThat(exception.columnName).isEqualTo("public_id")
        assertThat(exception.value).isEqualTo("game-1")
        assertThat(exception)
            .hasMessage("No row in 'game' where 'public_id' = game-1, referenced entity not found")
    }

    @Test
    fun `BatchFailedException uses the first failure as cause and suppresses the rest`() {
        // given
        val first = IllegalArgumentException("first")
        val second = IllegalStateException("second")
        val third = RuntimeException("third")

        // when
        val exception = BatchFailedException(listOf(first, second, third))

        // then
        assertThat(exception.failures).containsExactly(first, second, third)
        assertThat(exception.cause).isSameAs(first)
        assertThat(exception.suppressed).containsExactly(second, third)
        assertThat(exception).hasMessage("3 batch item(s) failed, first: first")
    }
}
