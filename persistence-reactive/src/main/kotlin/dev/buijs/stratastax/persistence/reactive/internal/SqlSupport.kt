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
package dev.buijs.stratastax.persistence.reactive.internal

import org.jooq.Condition
import org.jooq.Field
import org.jooq.Record
import org.jooq.Table
import org.jooq.TableField
import org.jooq.impl.DSL
import java.util.UUID

/** This field with its value type erased, for building conditions over values of unknown type. */
@Suppress("UNCHECKED_CAST")
internal fun Field<*>.untyped(): Field<Any?> = this as Field<Any?>

/** This column with its record and value type erased. */
@Suppress("UNCHECKED_CAST")
internal fun TableField<*, *>.untyped(): TableField<Record, Any?> = this as TableField<Record, Any?>

/** The table of this column. */
internal fun TableField<*, *>.owningTable(): Table<*> =
    table ?: throw IllegalArgumentException("Column '$name' is not bound to a table")

/** `this = other`. */
internal fun Field<*>.joinedOn(other: Field<*>): Condition = untyped().eq(other.untyped())

/**
 * `this` matches one of [values], `NULL` included when [values] contains `null` (where SQL's own
 * `IN` never matches `NULL`). Many values of a plain id type are bound as one array (`= ANY(?)`),
 * so the SQL text - and the prepared statement - does not depend on the number of values.
 */
internal fun Field<*>.isOneOf(values: Collection<Any?>): Condition {
    val column = untyped()
    val nonNull = values.filterNotNull().distinct()
    val matchesValue =
        when {
            nonNull.isEmpty() -> {
                null
            }
            nonNull.size == 1 -> {
                column.eq(nonNull.single())
            }
            bindsAsArray() -> {
                column.eq(
                    DSL.any(DSL.`val`(arrayOf(nonNull), dataType.array()).untyped()),
                )
            }
            else -> {
                column.`in`(nonNull)
            }
        }

    val matchesNull = if (values.any { it == null }) column.isNull else null
    return DSL.or(listOfNotNull(matchesValue, matchesNull).ifEmpty { listOf(DSL.falseCondition()) })
}

/** `this` matches none of [values]; with a `null` among them, `NULL` does not match either. */
internal fun Field<*>.isNoneOf(values: Collection<Any?>): Condition {
    val column = untyped()
    val nonNull = values.filterNotNull().distinct()
    val notAValue = if (nonNull.isEmpty()) null else column.notIn(nonNull)
    val notNull = if (values.any { it == null }) column.isNotNull else null
    val conditions = listOfNotNull(notAValue, notNull)
    // NOT IN (a, b) is never true for a NULL column either, so the result is consistent with SQL.
    return DSL.and(conditions)
}

private val ARRAY_BINDABLE_TYPES: Set<Class<*>> =
    setOf(UUID::class.java, java.lang.Long::class.java, Integer::class.java, String::class.java)

private fun Field<*>.bindsAsArray(): Boolean {
    val converter = dataType.converter
    return type in ARRAY_BINDABLE_TYPES && converter.fromType() == converter.toType()
}

@Suppress("UNCHECKED_CAST")
private fun Field<*>.arrayOf(values: List<Any>): Any {
    val array =
        java.lang.reflect.Array
            .newInstance(type, values.size) as Array<Any?>
    values.forEachIndexed { index, value -> array[index] = value }

    return array
}
