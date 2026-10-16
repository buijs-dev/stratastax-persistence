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

/**
 * Runs several persistence calls as one atomic unit: either every write inside [transaction]'s
 * block commits, or none does.
 *
 * Every create/update/delete call made inside the block (directly, or from any suspend function it
 * calls) takes part in the same transaction, and so does every read: a read sees the block's own
 * uncommitted writes. A call that fails inside the block only undoes its own work (it runs inside a
 * savepoint), so the block may catch that failure and carry on; an exception that escapes the block
 * rolls back everything.
 *
 * Keep slow, non-database work (HTTP calls, LLM requests) outside the block: a transaction holds a
 * pooled connection for its whole duration. A transaction is bound to one coroutine and must not be
 * used from several coroutines concurrently.
 */
interface UnitOfWork {
    suspend fun <T> transaction(
        options: TransactionOptions = TransactionOptions.DEFAULT,
        block: suspend () -> T,
    ): T
}

/**
 * [isolation] and [readOnly] only apply when a new transaction is started; a call that joins an
 * already running transaction ([TransactionPropagation.REQUIRED]) inherits that transaction's own
 * settings. `null` [isolation] means the database default (`READ COMMITTED` for Postgres).
 */
data class TransactionOptions(
    val isolation: TransactionIsolation? = null,
    val readOnly: Boolean = false,
    val propagation: TransactionPropagation = TransactionPropagation.REQUIRED,
) {
    companion object {
        val DEFAULT = TransactionOptions()
    }
}

enum class TransactionIsolation {
    READ_COMMITTED,
    REPEATABLE_READ,
    SERIALIZABLE,
}

enum class TransactionPropagation {
    /** Join the running transaction (inside a savepoint) or start a new one if there is none. */
    REQUIRED,

    /**
     * Always start a new, independent transaction on its own connection; it commits or rolls back
     * regardless of the outcome of any transaction already running.
     */
    REQUIRES_NEW,
}
