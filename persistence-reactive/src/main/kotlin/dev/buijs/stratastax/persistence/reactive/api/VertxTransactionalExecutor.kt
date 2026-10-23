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

import dev.buijs.stratastax.persistence.core.api.TransactionOptions
import dev.buijs.stratastax.persistence.core.api.TransactionPropagation
import dev.buijs.stratastax.persistence.core.spi.QueryExecutionListener
import dev.buijs.stratastax.persistence.reactive.internal.ActiveTransaction
import dev.buijs.stratastax.persistence.reactive.internal.TransactionContextElement
import dev.buijs.stratastax.persistence.reactive.internal.currentTransaction
import dev.buijs.stratastax.persistence.reactive.spi.PersistenceDialect
import dev.buijs.stratastax.persistence.reactive.spi.QueryExecutor
import dev.buijs.stratastax.persistence.reactive.spi.TransactionalExecutor
import io.smallrye.mutiny.coroutines.awaitSuspending
import io.vertx.mutiny.sqlclient.Pool
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.withContext
import java.time.Clock
import java.util.UUID
import kotlin.coroutines.CoroutineContext

/**
 * A [TransactionalExecutor] over a Vert.x Mutiny [Pool].
 *
 * A new transaction takes one connection from the pool, runs `BEGIN`, the block, and `COMMIT` -
 * or `ROLLBACK` when the block throws or is cancelled. The rollback always completes before the
 * connection goes back to the pool, so a pooled connection never carries an open transaction. The
 * transaction-control statements run through the same [VertxQueryExecutor] as the block's queries,
 * so every [listeners] entry sees the complete statement sequence under one transaction id.
 *
 * A call made while a transaction of this executor runs, with
 * [TransactionPropagation.REQUIRED], joins it inside a `SAVEPOINT`: a database such as Postgres
 * refuses every further statement of a transaction in which one failed, so the savepoint is what
 * lets a caller catch a failed nested call and carry on. [TransactionPropagation.REQUIRES_NEW] always takes a new
 * connection.
 *
 * Plain `BEGIN`/`COMMIT` statements are used rather than Vert.x's `Pool.withTransaction`, which
 * rolls the whole transaction back as soon as one statement fails and so rules out savepoints.
 *
 * The running transaction travels in the coroutine context, matched to [pool] by identity. The
 * block runs on [dispatcher]: pass a dispatcher that propagates the request context (e.g. a
 * MicroProfile `ManagedExecutor`) when code inside the block needs it. [clock] stamps the start of
 * every reported statement, see [VertxQueryExecutor].
 */
public class VertxTransactionalExecutor(
    private val pool: Pool,
    private val dialect: PersistenceDialect,
    private val listeners: List<QueryExecutionListener> = emptyList(),
    private val dispatcher: CoroutineContext = Dispatchers.Default,
    private val clock: Clock = Clock.systemUTC(),
) : TransactionalExecutor {

    /**
     * The [QueryExecutor] the access classes should get: it joins this executor's running
     * transaction when there is one, and runs on the pool otherwise.
     */
    public fun ambientQueryExecutor(): QueryExecutor =
        AmbientQueryExecutor(pool, VertxQueryExecutor(pool, dialect, listeners, clock = clock))

    override suspend fun isInTransaction(): Boolean = currentTransaction(pool) != null

    override suspend fun <T> withTransaction(
        options: TransactionOptions,
        block: suspend (QueryExecutor) -> T,
    ): T {
        val running = currentTransaction(pool)
        return if (running != null && options.propagation == TransactionPropagation.REQUIRED) {
            require(!options.readOnly || running.readOnly) {
                "A read-only transaction was requested inside a running read-write transaction"
            }

            withSavepoint(running, block)
        } else {
            withNewTransaction(options, block)
        }
    }

    private suspend fun <T> withNewTransaction(
        options: TransactionOptions,
        block: suspend (QueryExecutor) -> T,
    ): T {
        val connection = pool.connection.awaitSuspending()
        try {
            val executor =
                VertxQueryExecutor(
                    client = connection,
                    dialect = dialect,
                    listeners = listeners,
                    transactionId = UUID.randomUUID().toString(),
                    clock = clock,
                )

            val transaction =
                ActiveTransaction(
                    owner = pool,
                    executor = executor,
                    readOnly = options.readOnly,
                )

            val element =
                currentCoroutineContext()[TransactionContextElement]?.plus(transaction)
                    ?: TransactionContextElement(listOf(transaction))

            return withContext(dispatcher + element) {
                val result =
                    try {
                        executor.executeStatement(dialect.transactions.begin(options))
                        block(executor)
                    } catch (e: Throwable) {
                        transaction.completed = true
                        executor.executeSuppressingInto(e, dialect.transactions.rollback())
                        throw e
                    }

                transaction.completed = true
                executor.executeStatement(dialect.transactions.commit())
                result
            }
        } finally {
            withContext(NonCancellable) { connection.close().awaitSuspending() }
        }
    }

    private suspend fun <T> withSavepoint(
        transaction: ActiveTransaction,
        block: suspend (QueryExecutor) -> T,
    ): T {
        val savepoint = transaction.nextSavepointName()
        val sql = dialect.transactions
        transaction.executor.executeStatement(sql.savepoint(savepoint))
        val result =
            try {
                block(transaction.executor)
            } catch (e: Throwable) {
                transaction.executor.executeSuppressingInto(e, sql.rollbackToSavepoint(savepoint))
                throw e
            }

        transaction.executor.executeStatement(sql.releaseSavepoint(savepoint))
        return result
    }
}

/**
 * Runs [sql] even while the calling coroutine is cancelled - it undoes the work [failure] left -
 * and attaches its own failure to [failure] instead of hiding the original error.
 */
private suspend fun VertxQueryExecutor.executeSuppressingInto(
    failure: Throwable,
    sql: String,
) {
    try {
        withContext(NonCancellable) { executeStatement(sql) }
    } catch (undoFailure: Throwable) {
        failure.addSuppressed(undoFailure)
    }
}
