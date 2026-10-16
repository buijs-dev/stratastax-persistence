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

import dev.buijs.stratastax.ambient.context.api.PropagatedContextElement
import dev.buijs.stratastax.persistence.reactive.api.QueryExecutor
import kotlinx.coroutines.currentCoroutineContext
import java.util.concurrent.atomic.AtomicInteger
import kotlin.coroutines.CoroutineContext

/**
 * One running transaction: the [executor] bound to its connection, owned by the executor that
 * started it ([owner] - the pool, so several pools never mix up each other's transactions).
 */
internal class ActiveTransaction(
    val owner: Any,
    val executor: QueryExecutor,
    val statements: TransactionStatements,
) {

    @Volatile var completed: Boolean = false

    private val savepointCounter = AtomicInteger()

    fun nextSavepointName(): String = "stx_sp_${savepointCounter.incrementAndGet()}"
}

/** Issues raw transaction-control statements (`BEGIN`, `SAVEPOINT`, ...) on the connection. */
internal fun interface TransactionStatements {

    suspend fun execute(sql: String)
}

/**
 * Carries the running transactions down the coroutine. A [PropagatedContextElement], so it survives
 * every `currentPropagatedContext()`/`inheritedUni` boundary just like the ambient security
 * identity does - which is what lets a nested access call find (and join) the transaction its
 * caller started.
 */
internal class TransactionContextElement(
    private val transactions: List<ActiveTransaction>,
) : PropagatedContextElement {

    companion object Key : CoroutineContext.Key<TransactionContextElement>

    override val key: CoroutineContext.Key<TransactionContextElement>
        get() = Key

    /**
     * Matches [owner] by identity, never by `equals`: the owner is typically an injected CDI bean,
     * and an Arc client proxy is not even `equals` to itself (it delegates to the proxied
     * instance's `equals`, which rejects the proxy's class) - an `equals`-based lookup silently
     * never found the running transaction, so every nested call opened a transaction of its own.
     */
    operator fun get(owner: Any): ActiveTransaction? =
        transactions.firstOrNull { it.owner === owner }

    operator fun plus(transaction: ActiveTransaction): TransactionContextElement =
        TransactionContextElement(
            transactions.filterNot { it.owner === transaction.owner } + transaction,
        )
}

/**
 * The transaction [owner] is running in the current coroutine, or `null`.
 *
 * @throws IllegalStateException when the coroutine still carries a transaction that has already
 *   committed or rolled back - e.g. a coroutine launched inside a transaction that outlived it.
 */
internal suspend fun currentTransaction(owner: Any): ActiveTransaction? {
    val transaction =
        currentCoroutineContext()[TransactionContextElement]?.get(owner) ?: return null
    check(!transaction.completed) {
        "The transaction this coroutine was started in has already completed - " +
            "a coroutine must not outlive the transaction it was launched from"
    }

    return transaction
}

/** Whether the current coroutine runs inside any transaction at all. */
internal suspend fun isInTransaction(): Boolean =
    currentCoroutineContext()[TransactionContextElement] != null
