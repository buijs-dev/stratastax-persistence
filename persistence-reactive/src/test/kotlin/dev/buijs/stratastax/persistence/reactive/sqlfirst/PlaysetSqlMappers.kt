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
package dev.buijs.stratastax.persistence.reactive.sqlfirst

import dev.buijs.stratastax.entity.core.api.PatchField
import dev.buijs.stratastax.persistence.reactive.spi.Reference
import dev.buijs.stratastax.persistence.reactive.spi.patchParams
import dev.buijs.stratastax.persistence.reactive.testfixtures.CreateNote
import dev.buijs.stratastax.persistence.reactive.testfixtures.CreatePlayset
import dev.buijs.stratastax.persistence.reactive.testfixtures.CreateReaction
import dev.buijs.stratastax.persistence.reactive.testfixtures.UpdatePlayset
import io.vertx.core.json.JsonObject
import java.util.UUID

/**
 * The parameters of the create statements of [PlaysetSql], in placeholder order, as
 * stratastax-codegen generates them from the create command (written by hand here). Generated
 * values - ids, timestamps, the current user - would be filled in here too.
 */
internal class PlaysetCreateMapper {

    /** [PlaysetSql.INSERT]. */
    fun insert(
        command: CreatePlayset,
        loadoutId: Long?,
    ): List<Any?> =
        listOf(
            command.id, // $1 public_id
            command.name, // $2 name
            command.attributesJson?.let(::JsonObject), // $3 attributes
            loadoutId, // $4 loadout_id
            command.gameId, // $5 game_id, by game.public_id
            command.ownerId, // $6 owner_id, by app_user.public_id
        )

    /** The rows [PlaysetSql.INSERT] references. */
    fun references(command: CreatePlayset): List<Reference> =
        listOf(
            Reference("game", "public_id", PlaysetSql.GAME_EXISTS, command.gameId),
            Reference("app_user", "public_id", PlaysetSql.OWNER_EXISTS, command.ownerId),
        )

    /** [PlaysetSql.INSERT_NOTE]. */
    fun insertNote(
        note: CreateNote,
        playsetId: Long,
        loadoutId: Long?,
    ): List<Any?> =
        listOf(
            note.id, // $1 public_id
            note.text, // $2 text
            playsetId, // $3 playset_id
            loadoutId, // $4 loadout_id
            note.gameId, // $5 game_id, by game.public_id
        )

    /** The rows [PlaysetSql.INSERT_NOTE] and [PlaysetSql.UPDATE_NOTE] reference. */
    fun noteReferences(note: CreateNote): List<Reference> =
        listOf(Reference("game", "public_id", PlaysetSql.GAME_EXISTS, note.gameId))

    /** [PlaysetSql.INSERT_REACTION]. */
    fun insertReaction(
        reaction: CreateReaction,
        noteId: UUID,
    ): List<Any?> = listOf(reaction.id, reaction.emoji, noteId)
}

/** The parameters of the update statements of [PlaysetSql], in placeholder order. */
internal class PlaysetUpdateMapper {

    /** [PlaysetSql.UPDATE]: per patched column whether it is set, and its value. */
    fun update(
        id: UUID,
        expectedVersion: Any?,
        command: UpdatePlayset,
    ): List<Any?> =
        listOf(id, expectedVersion) + // $1 public_id, $2 version
            command.name.patchParams() + // $3, $4 name
            command.attributesJson.patchParams { JsonObject(it) } + // $5, $6 attributes
            command.gameId.patchParams() // $7, $8 game_id, by game.public_id

    /** The rows [PlaysetSql.UPDATE] references. */
    fun references(command: UpdatePlayset): List<Reference> =
        listOf(Reference("game", "public_id", PlaysetSql.GAME_EXISTS, command.gameId.valueOrNull()))

    /**
     * The expansions to link, or `null` when the command leaves the links as they are; a `null`
     * value unlinks them all.
     */
    fun expansions(command: UpdatePlayset): Set<UUID>? =
        when (val ids = command.expansionIds) {
            PatchField.Unset -> null
            is PatchField.Value -> ids.value.orEmpty()
        }

    /** [PlaysetSql.UPDATE_NOTE]. */
    fun updateNote(
        note: CreateNote,
        playsetId: Long,
    ): List<Any?> = listOf(note.id, note.text, playsetId, note.gameId)

    /** [PlaysetSql.UPDATE_REACTION]. */
    fun updateReaction(
        reaction: CreateReaction,
        noteId: UUID,
    ): List<Any?> = listOf(reaction.id, reaction.emoji, noteId)
}

private fun <T> PatchField<T>.valueOrNull(): T? = (this as? PatchField.Value)?.value
