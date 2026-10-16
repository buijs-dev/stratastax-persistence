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
package dev.buijs.stratastax.persistence.reactive.api

import dev.buijs.stratastax.ambient.context.api.currentPropagatedContext
import dev.buijs.stratastax.ambient.context.api.inheritedUni
import dev.buijs.stratastax.persistence.core.api.TransactionOptions
import dev.buijs.stratastax.persistence.core.api.TransactionPropagation
import dev.buijs.stratastax.persistence.core.spi.QueryExecutionListener
import dev.buijs.stratastax.persistence.reactive.app.ActiveTransaction
import dev.buijs.stratastax.persistence.reactive.app.PostgresSqlRenderer
import dev.buijs.stratastax.persistence.reactive.app.TransactionContextElement
import dev.buijs.stratastax.persistence.reactive.app.TransactionStatements
import dev.buijs.stratastax.persistence.reactive.app.currentTransaction
import io.smallrye.mutiny.coroutines.awaitSuspending
import io.vertx.mutiny.sqlclient.Pool
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import java.util.UUID
import kotlin.coroutines.CoroutineContext

/**
 * [TransactionalExecutor] over a Vert.x Mutiny [Pool]. A new transaction checks out one connection
 * ([Pool.withConnection]), issues `BEGIN`, runs [withTransaction]'s block and ends with `COMMIT`,
 * or with `ROLLBACK` when the block throws. A fresh [VertxQueryExecutor] is created per
 * transaction, scoped to that one connection (which is itself an `SqlClient`), so every query the
 * block issues through it runs in the same transaction - [renderer]/[listeners] are forwarded to it
 * unchanged, and it tags every statement with the transaction's own id. The transaction-control
 * statements (`BEGIN`, `SAVEPOINT`, `COMMIT`, ...) run through that same executor, so a listener
 * observes a transaction's complete statement sequence, not just its data queries.
 *
 * The transaction is deliberately driven with plain `BEGIN`/`COMMIT`/`ROLLBACK` statements rather
 * than Vert.x's own `Pool.withTransaction`: that API rolls the *whole* transaction back as soon as
 * a single statement fails, which makes savepoints (below) impossible.
 *
 * `Pool.withConnection { connection -> ... }`'s callback is a plain Vert.x reactive-pool callback,
 * not a coroutine resumption - nothing propagates into it automatically, which is why whatever
 * ambient context the block's caller was carrying (e.g. a `SecurityIdentity`) must be captured via
 * [currentPropagatedContext] *before* entering it, and re-installed via [inheritedUni] on the other
 * side.
 *
 * The running transaction itself travels the same way, as a [TransactionContextElement] matched to
 * [pool] by identity (never `equals` - see [TransactionContextElement.get]): a nested
 * [withTransaction] call (e.g. a `create()` called from inside a
 * [dev.buijs.stratastax.persistence.core.api.UnitOfWork.transaction] block) finds it and, with
 * [TransactionPropagation.REQUIRED], joins it inside a `SAVEPOINT` instead of opening a second
 * connection. The savepoint matters: Postgres refuses every further statement of a transaction in
 * which one statement failed, so without it a caller could never catch a failed nested call and
 * carry on (which every batch fallback - see `JooqCreateAccess.createAll` - relies on). An
 * [AmbientQueryExecutor] created for the same [pool] routes reads into the running transaction too.
 *
 * [dispatcher] defaults to [Dispatchers.Default] - correct only for a consumer with no Quarkus
 * request-scoped CDI dependencies inside the block. `quarkus-starter`'s `PersistenceProducer`
 * supplies `managedExecutor.asCoroutineDispatcher()` instead, the fix for a real bug where
 * `@Authenticated`/other `@RequestScoped`-dependent code called from inside the block threw
 * `ContextNotActiveException` on `Dispatchers.Default`'s worker threads - see [inheritedUni]'s own
 * kdoc.
 */
class VertxTransactionalExecutor(
    private val pool: Pool,
    private val renderer: SqlRenderer = PostgresSqlRenderer(),
    private val listeners: List<QueryExecutionListener> = emptyList(),
    private val dispatcher: CoroutineContext = Dispatchers.Default,
) : TransactionalExecutor {

    /**
     * The [QueryExecutor] the access classes should be given: joins this executor's running
     * transaction when there is one, and otherwise runs on the pool directly.
     */
    fun ambientQueryExecutor(): QueryExecutor =
        AmbientQueryExecutor(pool, VertxQueryExecutor(pool, renderer, listeners))

    override suspend fun <T> withTransaction(
        options: TransactionOptions,
        block: suspend (QueryExecutor) -> T,
    ): T {
        val running = currentTransaction(pool)
        return if (running != null && options.propagation == TransactionPropagation.REQUIRED) {
            withSavepoint(running, block)
        } else {
            withNewTransaction(options, block)
        }
    }

    private suspend fun <T> withNewTransaction(
        options: TransactionOptions,
        block: suspend (QueryExecutor) -> T,
    ): T {
        val propagated = currentPropagatedContext()
        return pool
            .withConnection { connection ->
                val executor =
                    VertxQueryExecutor(
                        connection,
                        renderer,
                        listeners,
                        transactionId = UUID.randomUUID().toString(),
                    )
                val statements = TransactionStatements(executor::executeStatement)
                val transaction =
                    ActiveTransaction(owner = pool, executor = executor, statements = statements)
                val transactions =
                    propagated[TransactionContextElement]?.plus(transaction)
                        ?: TransactionContextElement(listOf(transaction))
                inheritedUni(propagated + transactions, dispatcher) {
                    statements.execute(options.toBeginSql())
                    val result =
                        try {
                            block(transaction.executor)
                        } catch (e: Throwable) {
                            transaction.completed = true
                            statements.executeSuppressingInto(e, "ROLLBACK")
                            throw e
                        }

                    transaction.completed = true
                    statements.execute("COMMIT")
                    result
                }
            }.awaitSuspending()
    }

    private suspend fun <T> withSavepoint(
        transaction: ActiveTransaction,
        block: suspend (QueryExecutor) -> T,
    ): T {
        val savepoint = transaction.nextSavepointName()
        transaction.statements.execute("SAVEPOINT $savepoint")
        val result =
            try {
                block(transaction.executor)
            } catch (e: Throwable) {
                transaction.statements.executeSuppressingInto(e, "ROLLBACK TO SAVEPOINT $savepoint")
                throw e
            }

        transaction.statements.execute("RELEASE SAVEPOINT $savepoint")
        return result
    }
}

/**
 * Runs [sql] even when the calling coroutine is being cancelled - it undoes work after [failure] -
 * and attaches its own failure to [failure] instead of hiding the original error.
 */
private suspend fun TransactionStatements.executeSuppressingInto(
    failure: Throwable,
    sql: String,
) {
    try {
        withContext(NonCancellable) { execute(sql) }
    } catch (undoFailure: Throwable) {
        failure.addSuppressed(undoFailure)
    }
}

/** `BEGIN`, plus the transaction modes for [TransactionOptions] that deviate from the defaults. */
internal fun TransactionOptions.toBeginSql(): String {
    val modes =
        buildList {
            isolation?.let { add("ISOLATION LEVEL ${it.name.replace('_', ' ')}") }

            if (readOnly) add("READ ONLY")
        }

    return if (modes.isEmpty()) "BEGIN" else modes.joinToString(", ", prefix = "BEGIN ")
}
