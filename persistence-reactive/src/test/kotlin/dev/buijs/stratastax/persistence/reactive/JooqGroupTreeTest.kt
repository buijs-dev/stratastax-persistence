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

import dev.buijs.stratastax.persistence.reactive.api.JooqFieldGroup
import dev.buijs.stratastax.persistence.reactive.api.JooqGroupTree
import dev.buijs.stratastax.persistence.reactive.testfixtures.GAME
import dev.buijs.stratastax.persistence.reactive.testfixtures.GHOST_PLAYER
import dev.buijs.stratastax.persistence.reactive.testfixtures.MATCH
import dev.buijs.stratastax.persistence.reactive.testfixtures.MATCH_PARTICIPANT
import dev.buijs.stratastax.search.core.spi.FieldGroupRelation
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * Regression test: [JooqGroupTree] used to resolve group hierarchy by slicing dots out of
 * [JooqFieldGroup.name] (`substringBeforeLast('.', "")`). That assumption doesn't hold in general -
 * a group's [JooqFieldGroup.name] is an internal identifier whose exact format isn't part of any
 * contract (and in practice can be a dot-free camelCase string). These fixtures use dot-free names
 * on purpose to prove [JooqGroupTree.parentOf]/[JooqGroupTree.childrenOf] work via the explicit
 * [JooqFieldGroup.parentName] instead of parsing [JooqFieldGroup.name].
 */
internal class JooqGroupTreeTest {

    private val root =
        JooqFieldGroup(
            name = "game",
            relation = FieldGroupRelation.OneToOne,
            from = MATCH.ID,
            to = GAME.ID,
            fieldNames = listOf("gameName"),
            parentName = null,
        )

    private val nested =
        JooqFieldGroup(
            name = "participantsGhostPlayer",
            relation = FieldGroupRelation.OneToOne,
            from = MATCH_PARTICIPANT.GHOST_PLAYER_ID,
            to = GHOST_PLAYER.ID,
            fieldNames = listOf("participantsGhostPlayerDisplayName"),
            parentName = "participants",
        )

    private val parentOfNested =
        JooqFieldGroup(
            name = "participants",
            relation = FieldGroupRelation.OneToMany,
            from = MATCH.ID,
            to = MATCH_PARTICIPANT.MATCH_ID,
            fieldNames = listOf("participantsName"),
            parentName = null,
        )

    private val tree = JooqGroupTree(listOf(root, parentOfNested, nested))

    @Test
    fun `root-level group has no parent`() {
        assertThat(tree.parentOf(root)).isNull()
    }

    @Test
    fun `nested group resolves its parent via parentName, not by parsing name`() {
        val resolved = tree.parentOf(nested)
        assertThat(resolved).isEqualTo(parentOfNested)
    }

    @Test
    fun `childrenOf null returns only root-level groups`() {
        assertThat(tree.childrenOf(null).toSet()).isEqualTo(setOf(root, parentOfNested))
    }

    @Test
    fun `childrenOf a group name returns its nested children`() {
        assertThat(tree.childrenOf("participants")).isEqualTo(listOf(nested))
    }

    @Test
    fun `groupFor resolves via fieldNames regardless of name format`() {
        assertThat(tree.groupFor("participantsGhostPlayerDisplayName")).isEqualTo(nested)
        assertThat(tree.groupFor("gameName")).isEqualTo(root)
    }
}
