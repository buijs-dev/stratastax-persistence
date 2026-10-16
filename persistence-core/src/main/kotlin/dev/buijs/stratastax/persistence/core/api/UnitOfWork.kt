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
 * Every read and write made inside the block (directly, or from any suspend function it calls)
 * takes part in the same transaction, so a read sees the block's own uncommitted writes. A
 * persistence call that fails inside the block only undoes its own work (it runs inside a
 * savepoint): the block may catch that failure and carry on. An exception that escapes the block
 * rolls back everything.
 *
 * Keep slow, non-database work (HTTP calls, LLM requests) outside the block: a transaction holds a
 * pooled connection for its whole duration. A transaction is bound to one coroutine and must not be
 * used from several coroutines concurrently.
 */
public interface UnitOfWork {

    /**
     * Runs [block] in a transaction configured by [options] and returns its result.
     *
     * @throws Throwable whatever [block] throws, after the transaction (or savepoint) was rolled
     *   back.
     */
    public suspend fun <T> transaction(
        options: TransactionOptions = TransactionOptions.DEFAULT,
        block: suspend () -> T,
    ): T
}

/**
 * How [UnitOfWork.transaction] runs its block.
 *
 * [isolation] and [readOnly] apply when a new transaction is started. A call that joins a running
 * transaction ([TransactionPropagation.REQUIRED]) inherits that transaction's settings, except that
 * joining a read-write transaction with [readOnly] set is rejected rather than silently writing.
 *
 * @property isolation the isolation level, `null` for the database default (`READ COMMITTED` on
 *   Postgres).
 * @property readOnly whether the transaction may only read.
 * @property propagation whether to join a running transaction or always start a new one.
 */
public data class TransactionOptions(
    val isolation: TransactionIsolation? = null,
    val readOnly: Boolean = false,
    val propagation: TransactionPropagation = TransactionPropagation.REQUIRED,
) {

    public companion object {

        /** Database-default isolation, read-write, [TransactionPropagation.REQUIRED]. */
        public val DEFAULT: TransactionOptions = TransactionOptions()
    }
}

/** The SQL standard isolation levels a transaction can run at. */
public enum class TransactionIsolation {
    READ_COMMITTED,
    REPEATABLE_READ,
    SERIALIZABLE,
}

/** What happens when a transaction is requested while one is already running. */
public enum class TransactionPropagation {

    /** Join the running transaction (inside a savepoint), or start a new one if there is none. */
    REQUIRED,

    /**
     * Always start a new, independent transaction on its own connection: it commits or rolls back
     * regardless of the outcome of the transaction already running.
     *
     * The outer transaction keeps its connection while the new one runs, so this needs two pooled
     * connections at once: with a small pool, many concurrent callers can exhaust it and wait on
     * each other.
     */
    REQUIRES_NEW,
}
