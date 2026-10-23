# Transactions

Every single write already runs in its own transaction. This page is about grouping **several**
calls into one atomic unit, and about the rules that keep transactions fast and safe.

## Unit of work

```kotlin
unitOfWork.transaction {
    val playset = playsets.create(command)
    notes.create(CreateNote(playset.id, "first"))
}   // COMMIT, or ROLLBACK when the block throws or is cancelled
```

Inject `UnitOfWork` where you need it. In Quarkus it is the `TransactionalExecutor` bean; by hand it
is the `VertxTransactionalExecutor` itself.

Every persistence call inside the block takes part in the transaction, also when made from another
suspend function the block calls. Reads inside the block see the block's own uncommitted writes.

### How calls find the transaction

There is no thread-local. The transaction is stored in the **coroutine context**, and the access
classes get an `AmbientQueryExecutor`: for each statement, it looks in the calling coroutine's
context for a running transaction, and falls back to the connection pool when there is none.

```kotlin
val transactional = VertxTransactionalExecutor(pool, PostgresDialect)
val queries = transactional.ambientQueryExecutor()   // give this to the access classes
```

So the same access class works inside and outside a unit of work, without passing a connection
around.

## A failing call does not doom the transaction

In PostgreSQL, once one statement of a transaction fails, every further statement is refused until
the rollback. That would make it impossible to catch an error and carry on.

So a persistence call inside a running transaction runs in a **savepoint**. When it fails, only its
own work is undone, and the block can continue:

```kotlin
unitOfWork.transaction {
    playsets.create(good)
    runCatching { playsets.create(invalid) }   // fails, and only this create is undone
    playsets.create(alsoGood)
}   // commits good and alsoGood
```

## Options

```kotlin
unitOfWork.transaction(TransactionOptions(isolation = TransactionIsolation.SERIALIZABLE)) { ... }
unitOfWork.transaction(TransactionOptions(readOnly = true)) { ... }
unitOfWork.transaction(TransactionOptions(propagation = TransactionPropagation.REQUIRES_NEW)) { ... }
```

| Option               | Effect                                                                                                |
|----------------------|-------------------------------------------------------------------------------------------------------|
| `isolation`          | `READ_COMMITTED` (the PostgreSQL default), `REPEATABLE_READ` or `SERIALIZABLE`, for a new transaction |
| `readOnly`           | a transaction that may only read. Asking for it inside a running read-write transaction is rejected.  |
| `REQUIRED` (default) | join the running transaction in a savepoint, or start a new one                                       |
| `REQUIRES_NEW`       | always a new transaction on its own connection, committed or rolled back independently                |

`REQUIRES_NEW` is for work that must survive the outer transaction, such as an audit record of an
attempt that is rolled back:

```kotlin
unitOfWork.transaction {
    unitOfWork.transaction(TransactionOptions(propagation = TransactionPropagation.REQUIRES_NEW)) {
        audit.create(attempt)      // committed on its own
    }
    payments.create(payment)       // may still roll back
}
```

A call that joins a running transaction inherits its isolation level and read-only mode.

## Rules of thumb

**Keep slow work out of the block.** A transaction holds a pooled connection for as long as it
runs. Make HTTP calls, LLM requests and other slow work before or after it:

```kotlin
val enriched = catalog.lookup(command.gameId)   // slow: outside
unitOfWork.transaction {
    playsets.create(command.with(enriched))     // fast: inside
}
```

**One statement at a time.** A transaction runs its statements one after the other. Running
statements of one transaction from several coroutines at once, for example with `async` inside the
block, fails with `IllegalStateException`.

**Do not let coroutines outlive the block.** A coroutine launched inside the block that is still
running after the transaction ended fails on its next query (`IllegalStateException`). It does not
quietly use a connection that has gone back to the pool.

**`REQUIRES_NEW` needs a second connection** while the outer transaction holds its own. With many
concurrent callers and a small pool, they can end up waiting for each other.

## Safety guarantees

- **Cancellation is safe.** When the caller is cancelled, the transaction is rolled back before its
  connection goes back to the pool. A pooled connection never carries an open transaction.
- **Listeners cannot break a transaction.** A `QueryExecutionListener` that throws is logged and
  ignored.

## Context propagation

The block runs on the executor's `dispatcher` (default `Dispatchers.Default`), with the caller's
coroutine context. Context elements, such as the security identity of `stratastax-ambient-context`,
are therefore visible inside the block.

In Quarkus, code in the block may need request-scoped beans. Then pass a dispatcher that also
carries the request context:

```kotlin
VertxTransactionalExecutor(pool, PostgresDialect, dispatcher = managedExecutor.asCoroutineDispatcher())
```

## Batches

`createAll`, `updateAll` and `deleteAll` are not atomic on their own: inputs that succeed are
committed even when others fail. See [Batch operations](batch-operations.md#all-or-nothing) for
how to make them all-or-nothing.

---

Next: [CSV import](csv-import.md)
