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

import dev.buijs.stratastax.ambient.context.api.PropagatedContextElement
import dev.buijs.stratastax.persistence.reactive.api.VertxQueryExecutor
import kotlinx.coroutines.currentCoroutineContext
import java.util.concurrent.atomic.AtomicInteger
import kotlin.coroutines.CoroutineContext

/**
 * One running transaction: the [executor] bound to its connection,
 * started by [owner] (the pool - several pools never mix up each other's transactions).
 */
internal class ActiveTransaction(
    val owner: Any,
    val executor: VertxQueryExecutor,
    val readOnly: Boolean,
) {

    @Volatile var completed: Boolean = false

    private val savepointCounter = AtomicInteger()

    fun nextSavepointName(): String = "stx_sp_${savepointCounter.incrementAndGet()}"
}

/**
 * Carries the running transactions down the coroutine. A [PropagatedContextElement], so it also
 * survives the context boundaries the ambient security identity crosses, which is what lets a
 * nested persistence call find (and join) the transaction its caller started.
 */
internal class TransactionContextElement(
    private val transactions: List<ActiveTransaction>,
) : PropagatedContextElement {

    companion object Key : CoroutineContext.Key<TransactionContextElement>

    override val key: CoroutineContext.Key<TransactionContextElement>
        get() = Key

    /**
     * The transaction of [owner], matched by identity: an injected bean may be a client proxy that
     * is not even `equals` to itself.
     */
    operator fun get(owner: Any): ActiveTransaction? =
        transactions.firstOrNull { it.owner === owner }

    /** These transactions, with [transaction] replacing any of the same owner. */
    operator fun plus(transaction: ActiveTransaction): TransactionContextElement =
        TransactionContextElement(
            transactions.filterNot { it.owner === transaction.owner } + transaction,
        )
}

/**
 * The transaction [owner] is running in the current coroutine, or `null`.
 *
 * @throws IllegalStateException when the coroutine still carries a transaction that already
 *   committed or rolled back; a coroutine launched inside a transaction that outlived it.
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
