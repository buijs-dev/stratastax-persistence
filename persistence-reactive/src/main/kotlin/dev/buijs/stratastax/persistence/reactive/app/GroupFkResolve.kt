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

import org.jooq.TableField

/**
 * One 1:1 group's identity-field create/update binding (see
 * `dev.buijs.stratastax.persistence.reactive.JooqOneToOneGroupFieldBuilder.create`/ `.update`) - a
 * create/update command supplies a value for [identityColumn] (typically a public id, e.g.
 * `GAME.PUBLIC_ID`), not [fkColumn]'s own raw value (a `Long` surrogate key, e.g.
 * `PLAYSET.GAME_ID`). [JooqCreateAccess]/ [JooqUpdateAccess] resolve the actual FK value inline, as
 * a joined derived table (`SELECT [targetColumn] FROM <[identityColumn]'s table> WHERE
 * [identityColumn] = <[valueSupplier]'s result>`) in the same statement as the write itself - no
 * separate round trip - throwing
 * [dev.buijs.stratastax.persistence.core.api.ReferencedEntityNotFoundException] when that lookup
 * finds no row.
 *
 * @property fkColumn The physical FK column on the *parent* table that gets written to, e.g.
 *   `PLAYSET.GAME_ID`.
 * @property identityColumn The group's own identity field's column, on the *target* table, e.g.
 *   `GAME.PUBLIC_ID`.
 * @property targetColumn The target table's own column [fkColumn] actually references, e.g.
 *   `GAME.ID` - what the resolve query selects.
 * @property valueSupplier Reads the identity value off the create/update command, e.g. `{ command
 *   -> command.gameId.value }`.
 * @property skip Returns `true` when this relation shouldn't be touched by this write *at all* -
 *   distinct from [valueSupplier] returning `null`, which means "set [fkColumn] to `NULL`" (see
 *   `FkResolveSupport`'s own `buildCreateQuery`/`buildUpdateQuery` kdoc). The default (`{ false }`,
 *   i.e. never skip) preserves every existing caller's behavior unchanged - only an `update()`-side
 *   binding whose command property is `dev.buijs.stratastax.entity.core.api.PatchField`-typed needs
 *   this, since `PatchField.Unset` means "the caller didn't mention this field", which has no other
 *   way to be distinguished from a genuine `null`/absent value here.
 */
class GroupFkResolve<C>(
    val fkColumn: TableField<*, *>,
    val identityColumn: TableField<*, *>,
    val targetColumn: TableField<*, *>,
    val valueSupplier: (C) -> Any?,
    val skip: (C) -> Boolean = { false },
)
