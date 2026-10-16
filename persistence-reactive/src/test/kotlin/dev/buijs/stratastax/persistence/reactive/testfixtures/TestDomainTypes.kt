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
package dev.buijs.stratastax.persistence.reactive.testfixtures

import java.time.LocalDateTime

/**
 * Shared toy domain model for group/relation DSL and translator tests.
 *
 * Named `MatchEntity` rather than `Match` - macOS's default case-insensitive filesystem collapses
 * the `Match.class` and `MATCH.class` (the fake jOOQ table object below) build outputs into one
 * file otherwise, corrupting whichever compiles second.
 *
 * [status]/[playedAt]/[deletedAt] exist purely as real bound properties for `field(MatchEntity::x,
 * ...) { }` call sites that need one - none of these tests read their actual runtime values (they
 * always supply their own `cursorValue { it.id }`/`fixedValue(...)`), so the defaults just need to
 * compile.
 */
internal data class MatchEntity(
    val id: String,
    val status: String = "active",
    val playedAt: LocalDateTime? = null,
    val deletedAt: LocalDateTime? = null,
    val game: GameRef? = null,
    val gameA: GameRef? = null,
    val gameB: GameRef? = null,
    val participants: List<MatchParticipant> = emptyList(),
    val tags: List<TagRef> = emptyList(),
    val attributes: Map<String, Any?> = emptyMap(),
)

internal data class GameRef(
    val id: String,
    val name: String,
)

internal data class MatchParticipant(
    val id: String,
    val name: String,
    val score: Int,
    val ghostPlayer: GhostPlayerRef? = null,
)

internal data class GhostPlayerRef(
    val id: String,
    val displayName: String,
)

internal data class TagRef(
    val id: String,
    val name: String,
)
