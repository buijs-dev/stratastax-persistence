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

import org.jooq.Record
import org.jooq.TableField
import org.jooq.impl.DSL
import org.jooq.impl.SQLDataType
import org.jooq.impl.TableImpl
import java.time.OffsetDateTime
import java.util.UUID

/**
 * Minimal hand-written fake table - see search-jooq's own `FakeTables.kt` for the same convention.
 */
internal object PLAYSET : TableImpl<Record>(DSL.name("playset")) {

    val ID: TableField<Record, UUID> = createField(DSL.name("id"), SQLDataType.UUID)

    /**
     * A second, unrelated UUID column - used only by the regression test covering a DSL `"id"`
     * field bound to a column other than [ID], to prove `RETURNING` selects the field's own bound
     * column rather than whatever physical column happens to share the field's DSL name.
     */
    val PUBLIC_ID: TableField<Record, UUID> = createField(DSL.name("public_id"), SQLDataType.UUID)
    val NAME: TableField<Record, String> = createField(DSL.name("name"), SQLDataType.VARCHAR)
    val VERSION: TableField<Record, Long> = createField(DSL.name("version"), SQLDataType.BIGINT)
    val DELETED_AT: TableField<Record, OffsetDateTime> =
        createField(DSL.name("deleted_at"), SQLDataType.TIMESTAMPWITHTIMEZONE)
    val GAME_ID: TableField<Record, Long> = createField(DSL.name("game_id"), SQLDataType.BIGINT)
    val OWNER_USER_ID: TableField<Record, Long> =
        createField(DSL.name("owner_user_id"), SQLDataType.BIGINT)
}

/**
 * A related table reached via a 1:1 group's identity-field `create(...)`/ `update(...)`
 * binding - [ID] (a `Long` surrogate key) is what [PLAYSET.GAME_ID] actually stores, while
 * [PUBLIC_ID] (a `UUID`) is what a create/update command supplies - see `GroupFkResolve`.
 */
// Named like the jOOQ-generated table it stands in for.
@Suppress("ktlint:standard:class-naming")
internal object PLAYSET_GAME : TableImpl<Record>(DSL.name("game")) {

    val ID: TableField<Record, Long> = createField(DSL.name("id"), SQLDataType.BIGINT)
    val PUBLIC_ID: TableField<Record, UUID> = createField(DSL.name("public_id"), SQLDataType.UUID)
}

/**
 * A second related table, reached the same way as [PLAYSET_GAME] - used to cover an entity with
 * *two* FK-bound 1:1 groups (e.g. `Playset.owner` in addition to `Playset.game`) so multi-group
 * FK-resolve fallback behavior can be tested.
 */
// Named like the jOOQ-generated table it stands in for.
@Suppress("ktlint:standard:class-naming")
internal object APP_USER : TableImpl<Record>(DSL.name("app_user")) {

    val ID: TableField<Record, Long> = createField(DSL.name("id"), SQLDataType.BIGINT)
    val PUBLIC_ID: TableField<Record, UUID> = createField(DSL.name("public_id"), SQLDataType.UUID)
}

/** Junction/link table for a many-to-many PLAYSET <-> "expansion" relation. */
// Named like the jOOQ-generated table it stands in for.
@Suppress("ktlint:standard:class-naming")
internal object PLAYSET_EXPANSION : TableImpl<Record>(DSL.name("playset_expansion")) {

    val PLAYSET_ID: TableField<Record, UUID> = createField(DSL.name("playset_id"), SQLDataType.UUID)
    val EXPANSION_ID: TableField<Record, UUID> =
        createField(DSL.name("expansion_id"), SQLDataType.UUID)
}

/**
 * The junction's own target table, reached via a `JunctionTargetResolve` - [ID] (a `Long` surrogate
 * key, what [PLAYSET_EXPANSION.EXPANSION_ID] really stores) vs. [PUBLIC_ID] (a `UUID`, what a
 * create/update command's own `expansionIds` actually supplies) - see [PLAYSET_GAME] for the same
 * shape on the 1:1 `GroupFkResolve` side.
 */
internal object EXPANSION : TableImpl<Record>(DSL.name("expansion")) {

    val ID: TableField<Record, Long> = createField(DSL.name("id"), SQLDataType.BIGINT)
    val PUBLIC_ID: TableField<Record, UUID> = createField(DSL.name("public_id"), SQLDataType.UUID)
}

/**
 * An owned direct-FK 1:N child table (unlike [PLAYSET_EXPANSION], a link table) - [ID] is the child
 * row's own real PK (`OneToManyUpdateWrite.childIdentityColumn`'s diff key), [PLAYSET_ID] the FK
 * back to [PLAYSET] (`OneToManyWrite.parentColumn`), [DELETED_AT] the optional soft-delete column
 * (`OneToManyUpdateWrite.softDeleteColumn`).
 */
// Named like the jOOQ-generated table it stands in for.
@Suppress("ktlint:standard:class-naming")
internal object PLAYSET_NOTE : TableImpl<Record>(DSL.name("playset_note")) {

    val ID: TableField<Record, UUID> = createField(DSL.name("id"), SQLDataType.UUID)
    val PLAYSET_ID: TableField<Record, UUID> = createField(DSL.name("playset_id"), SQLDataType.UUID)
    val TEXT: TableField<Record, String> = createField(DSL.name("text"), SQLDataType.VARCHAR)
    val DELETED_AT: TableField<Record, OffsetDateTime> =
        createField(DSL.name("deleted_at"), SQLDataType.TIMESTAMPWITHTIMEZONE)
    val VERSION: TableField<Record, Long> = createField(DSL.name("version"), SQLDataType.BIGINT)

    /**
     * A nested 1:1 group's own FK column, resolved per element rather than once per command - see
     * `OneToManyCreateWrite.elementFkResolves`/ `OneToManyUpdateWrite.elementFkResolves`.
     * [PLAYSET_GAME] doubles as the nested group's own target table here, exactly like it does for
     * [PLAYSET]'s own root-level `GroupFkResolve` tests.
     */
    val GAME_ID: TableField<Record, Long> = createField(DSL.name("game_id"), SQLDataType.BIGINT)
}

/**
 * A managed 1:N group nested one level inside [PLAYSET_NOTE]'s own child element -
 * `PlaysetNote.tags: List<Tag>` - covers
 * `OneToManyCreateWrite.elementWrites`/`OneToManyUpdateWrite.elementWrites` (a managed group nested
 * inside another managed group's own child, as opposed to [PLAYSET_GAME] above, a nested *identity*
 * group's own FK column).
 */
// Named like the jOOQ-generated table it stands in for.
@Suppress("ktlint:standard:class-naming")
internal object PLAYSET_NOTE_TAG : TableImpl<Record>(DSL.name("playset_note_tag")) {

    val ID: TableField<Record, UUID> = createField(DSL.name("id"), SQLDataType.UUID)
    val NOTE_ID: TableField<Record, UUID> = createField(DSL.name("note_id"), SQLDataType.UUID)
    val LABEL: TableField<Record, String> = createField(DSL.name("label"), SQLDataType.VARCHAR)
    val DELETED_AT: TableField<Record, OffsetDateTime> =
        createField(DSL.name("deleted_at"), SQLDataType.TIMESTAMPWITHTIMEZONE)
}

internal data class PlaysetEntity(
    val id: String,
    val name: String,
    val version: Long,
)

internal data class CreateNoteTag(
    val id: UUID,
    val label: String,
)

internal data class UpdateNoteTag(
    val id: UUID,
    val label: String,
)

internal data class CreatePlaysetNote(
    val id: UUID,
    val text: String,
    val gameId: UUID? = null,
    val tags: List<CreateNoteTag> = emptyList(),
)

internal data class UpdatePlaysetNote(
    val id: UUID,
    val text: String,
    val gameId: UUID? = null,
    val tags: List<UpdateNoteTag> = emptyList(),
)

internal data class CreatePlayset(
    val name: String,
    val expansionIds: Set<UUID> = emptySet(),
    val notes: List<CreatePlaysetNote> = emptyList(),
)

internal data class UpdatePlayset(
    val name: String,
    val expansionIds: Set<UUID> = emptySet(),
    val notes: List<UpdatePlaysetNote> = emptyList(),
)
