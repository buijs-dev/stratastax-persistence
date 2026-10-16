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
package dev.buijs.stratastax.persistence.reactive.app

import dev.buijs.stratastax.persistence.core.spi.CreateMapper
import dev.buijs.stratastax.persistence.core.spi.UpdateMapper
import org.jooq.Field
import org.jooq.Table
import org.jooq.TableField

/**
 * A top-level *managed* direct-FK 1:N group's own child rows (see
 * `dev.buijs.stratastax.persistence.reactive.JooqOneToManyGroupBuilder`'s
 * `isDirectFkShape`/`isOneToManyManaged`) - the child rows *are* the entity's own owned data (e.g.
 * `MATCH_PARTICIPANT` rows), unlike [JunctionWrite]'s link-table rows, which merely associate two
 * independently-existing rows.
 *
 * Create-side is insert-only - the parent row is itself new, so every element is unconditionally a
 * brand-new child row; see [OneToManyUpdateWrite] for the diff-based upsert an `update()` call
 * needs instead.
 *
 * @property childTable The child rows' own table, e.g. `MATCH_PARTICIPANT`.
 * @property parentColumn The child table's own FK column back to the parent, e.g.
 *   `MATCH_PARTICIPANT.MATCH_ID` (`JooqFieldGroup.to`).
 * @property parentRootColumn The parent table's own column [parentColumn] references, e.g.
 *   `MATCH.ID` (`JooqFieldGroup.from`) - read back off the parent's own `INSERT ... RETURNING` row
 *   via [junctionRootAlias], exactly like [JunctionWrite.parentRootColumn].
 * @property elements Reads the incoming child values off the create command, e.g. `{ command ->
 *   command.participants }`.
 * @property elementMapper Maps one element to its own column/value bag - a generated per-group
 *   `CreateMapper`, the same abstraction a root entity's own `${Entity}CreateMapper` already
 *   implements, just scoped to the child element type instead.
 * @property elementFkResolves A nested 1:1 group's own identity-field create binding, resolved per
 *   element rather than once per command.
 * @property childIdentityColumn This group's own child row's identity column, e.g.
 *   `MATCH_PARTICIPANT.PUBLIC_ID` - only required when [elementWrites] is non-empty (a
 *   *further*-nested managed 1:N group needs this element's own newly-generated id as *its* own
 *   [parentRootColumn] value); `null` for a leaf managed group with no managed children of its own.
 *   Unlike [OneToManyUpdateWrite]'s identically-named property (that group's own diff key), this is
 *   never used to look an existing row up - a create-side id is always fresh, generated
 *   application-side by [elementMapper] itself (baked into the [CreateValues] it returns, keyed by
 *   this same column) - see [childIdentityValue].
 * @property elementWrites A managed 1:N group nested (one level) inside *this* group's own child
 *   element (e.g. `MatchParticipant.achievements: List<Achievement>`) - each is written inside the
 *   same transaction, immediately after *this* element's own row, using this element's own
 *   [childIdentityColumn] value as that nested write's own [parentRootColumn] value. Recursion has
 *   no depth limit baked in here - however deep `JooqOneToManyGroupBuilder`'s own DSL nesting goes,
 *   this structure mirrors it one level at a time.
 * @property elementOwnedChildWrites A cascade-*owned* 1:1 `@PersistenceJoin(owned = true)` group
 *   nested inside *this* group's own child element (e.g. `MatchParticipant.loadout:
 *   MatchLoadout`) - unlike [elementFkResolves] (a public id resolved against an *existing* row via
 *   one batched lookup `SELECT`), each of these inserts a fresh child row - one at a time, per
 *   element, since each element's own child command differs - *before* this element's own row joins
 *   the batch `INSERT`, so its newly-generated id can be folded into that same row (see
 *   [OwnedChildCreateWrite]'s own kdoc for why this ordering is the whole point). Runs inside the
 *   same transaction as this element's own batch insert.
 */
class OneToManyCreateWrite<C, E>(
    val childTable: Table<*>,
    val parentColumn: TableField<*, *>,
    val parentRootColumn: TableField<*, *>,
    val elements: (C) -> Collection<E>,
    val elementMapper: CreateMapper<E, CreateValues>,
    val elementFkResolves: List<GroupFkResolve<E>> = emptyList(),
    val childIdentityColumn: TableField<*, *>? = null,
    val elementWrites: List<OneToManyCreateWrite<E, *>> = emptyList(),
    val elementOwnedChildWrites: List<OwnedChildCreateWrite<E, *>> = emptyList(),
) {

    /**
     * [childIdentityColumn]'s own value, read back out of [values] (the exact [CreateValues] this
     * write's own INSERT statement is about to bind) rather than recomputed - the id is generated
     * exactly once, by [elementMapper] itself, and this must see that same value, not a second,
     * independently-generated one. `null` when [childIdentityColumn] is unset (no [elementWrites]
     * to need it) or [values] has no entry for it (should be impossible whenever
     * [childIdentityColumn] *is* set, since codegen always includes it in [elementMapper]'s own
     * output for a managed group - see `OneToManyElementMapperRenderer`-equivalent codegen).
     */
    internal fun childIdentityValue(values: CreateValues): Any? =
        childIdentityColumn?.let { values.values[it] }
}

/**
 * Same shape as [OneToManyCreateWrite], for `update()` - diff-based upsert instead of unconditional
 * insert, since the parent row (and therefore some of its children) may already exist. For the
 * incoming [elements], a row whose own [elementId] matches an existing child row is `UPDATE`d; an
 * unmatched id is `INSERT`ed (a child added during this same `update()` call, its own id generated
 * the same way [OneToManyCreateWrite]'s would be); an existing child row whose id isn't present in
 * [elements] at all is removed - via [softDeleteColumn] when set, otherwise hard-`DELETE`d.
 *
 * @property childIdentityColumn The child row's own real PK, e.g. `MATCH_PARTICIPANT.ID`
 *   (`JooqFieldGroup.childIdentityColumn`) - the diff key.
 * @property softDeleteColumn Cascades a soft delete instead of a hard one for a removed child row,
 *   when set (`JooqFieldGroup.childSoftDeleteColumn`).
 * @property elements Reads the incoming child values off the update command - `null` means "don't
 *   touch this group's rows at all for this call" (a generated `${Entity}UpdateAccess` produces
 *   this for a `PatchField`-typed group binding left
 *   [dev.buijs.stratastax.entity.core.api.PatchField.Unset], via
 *   [dev.buijs.stratastax.entity.core.api.orNullIfUnset] - the exact same convention
 *   [JunctionWriteMapper.targetIds] already uses for a junction's own `PatchField`-typed binding).
 *   [JooqUpdateAccess] excludes a `null` result entirely from its own diff - not just "zero
 *   incoming elements", which would instead be read as "remove every existing child" (a real,
 *   distinct outcome for an explicit `Value(emptyList())`/`Value(null)`).
 * @property elementId Reads one element's own identity value - either an existing child's real id,
 *   or a freshly-generated one for a newly-added element within this same `update()` call. For an
 *   `INSERT` (an id with no existing match), this value is bound to [childIdentityColumn]
 *   automatically, in addition to whatever [elementMapper] itself maps - [elementMapper] only needs
 *   to cover the element's own non-identity fields, matching its role for an already-existing row's
 *   `UPDATE`.
 * @property elementWrites The update-side counterpart of [OneToManyCreateWrite.elementWrites] - a
 *   managed 1:N group nested one level inside *this* group's own child element. Unlike the create
 *   side, [elementId] (not a value extracted from [UpdateValues]) is already this element's own
 *   identity, for both a matched (existing) and a newly-added row - see [JooqUpdateAccess]'s own
 *   recursive resync for how a *removed* parent element also cascades into these before it's itself
 *   soft/hard deleted.
 * @property replaceAll When `true` (OneToManyUpdateStrategy.REPLACE_ALL), [JooqUpdateAccess] skips
 *   the id-based diff entirely: every existing row for the parent is removed (soft/hard, same as an
 *   ordinary removed row, cascading into [elementWrites] first) and every incoming element is
 *   inserted fresh, regardless of whether its own [elementId] happens to coincide with an existing
 *   row's. Default `false` preserves today's diff-based upsert. A group nested inside a
 *   `replaceAll` group needs no `replaceAll` of its own - see this class's own file-level kdoc on
 *   [OneToManyUpdateStrategy] for why the ordinary diff at that nested level already behaves as a
 *   full replace once its own parent id is always freshly generated.
 * @property elementOwnedChildWrites A cascade-*owned* 1:1 `@PersistenceJoin(owned = true)` group
 *   nested inside *this* group's own child element (e.g. `MatchParticipant.loadout`) - covers only
 *   a *removed* element's own owned child row disappearing alongside it (via [JooqUpdateAccess]'s
 *   own `cascadeRemoveElementWrites`/removed-rows handling). See [elementOwnedChildCreateWrites]
 *   for the insert-side counterpart.
 * @property elementOwnedChildCreateWrites The insert-side counterpart of
 *   [elementOwnedChildWrites] - a cascade-*owned* 1:1 group nested inside *this* group's own child
 *   element, covering an element going through [JooqUpdateAccess]'s own *insert* path: a
 *   newly-added element under the ordinary diff-based upsert, or - since [replaceAll] forces every
 *   incoming element through that same insert path - literally every element under `replaceAll`.
 *   Mirrors [OneToManyCreateWrite.elementOwnedChildWrites] exactly (one fresh owned child row
 *   inserted per element, before that element's own row joins the batch INSERT, so its
 *   newly-generated id can be folded into that same row). A *matched* (already-existing) element's
 *   own owned child isn't covered by this list at all - there's no update-in-place owned-child
 *   write support yet, only insert (this) and removal ([elementOwnedChildWrites]).
 * @property versionColumn The child's own version column, if it has one: every row
 *   [JooqUpdateAccess] *inserts* (a new element under the upsert diff, every element under
 *   [replaceAll]) starts at [initialVersion], as on create. A matched row's version is left alone:
 *   optimistic locking is on the root. [elementMapper] therefore never binds the version.
 * @property initialVersion see [versionColumn]
 *
 * [elements]/[elementId]/[elementMapper] default to inert stand-ins so an instance can be built for
 * [JooqDeleteAccess.deleteOneToManyWrites] without a real command/element type in
 * scope - [JooqDeleteAccess.delete] only ever feeds an instance's
 * [childTable]/[parentColumn]/[childIdentityColumn]/
 * [softDeleteColumn]/[elementWrites]/[elementOwnedChildWrites] into the shared
 * [cascadeRemoveElementWrites] cascade, never these three (only
 * [JooqUpdateAccess.resyncOneToManyElements]'s own diff reads them, and that only ever runs against
 * an [updateOneToManyWrites][JooqUpdateAccess.updateOneToManyWrites] entry, a structurally separate
 * list built with real values, same as today). `OneToManyDeleteEntryRenderer` is the only place
 * these defaults are relied on, always with `<Any?, Any?>` type arguments (no real `U`/`E` needed
 * either, for the same reason).
 */
class OneToManyUpdateWrite<U, E>(
    val childTable: Table<*>,
    val parentColumn: TableField<*, *>,
    val parentRootColumn: TableField<*, *>,
    val childIdentityColumn: TableField<*, *>,
    val softDeleteColumn: TableField<*, *>? = null,
    val elements: (U) -> Collection<E>? = { null },
    val elementId: (E) -> Any = {
        error("elementId not configured - delete-only OneToManyUpdateWrite")
    },
    val elementMapper: UpdateMapper<E, UpdateValues> =
        UpdateMapper {
            error("elementMapper not configured - delete-only OneToManyUpdateWrite")
        },
    val elementFkResolves: List<GroupFkResolve<E>> = emptyList(),
    val elementWrites: List<OneToManyUpdateWrite<E, *>> = emptyList(),
    val replaceAll: Boolean = false,
    val elementOwnedChildWrites: List<OwnedChildDeleteWrite> = emptyList(),
    val elementOwnedChildCreateWrites: List<OwnedChildCreateWrite<E, *>> = emptyList(),
    val versionColumn: Field<*>? = null,
    val initialVersion: InitialVersionStrategy? = null,
)
