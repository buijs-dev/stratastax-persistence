# Transactions

## Unit of work

`UnitOfWork.transaction { }` runs a block as one atomic unit. Inject the `UnitOfWork` (in Quarkus
it is the `TransactionalExecutor` bean) and call the repositories inside the block:

```kotlin
unitOfWork.transaction {
    val playset = playsets.create(command)
    notes.create(CreateNote(playset.id, "first"))
}   // COMMIT - or ROLLBACK when the block throws (or is cancelled)
```

Every persistence call in the block - and in any suspend function it calls - takes part in the
transaction: reads see the block's own uncommitted writes. This works because the repositories
use the `AmbientQueryExecutor` (`VertxTransactionalExecutor.ambientQueryExecutor()`), which runs a
query in the calling coroutine's transaction when there is one, and on the pool otherwise.

## Savepoints: a failing call does not doom the unit of work

A persistence call inside a running transaction runs in a `SAVEPOINT`. When it fails, only its own
work is undone (`ROLLBACK TO SAVEPOINT`), so the block may catch the failure and carry on:

```kotlin
unitOfWork.transaction {
    playsets.create(good)
    runCatching { playsets.create(invalid) }      // undone on its own
    playsets.create(alsoGood)
}   // commits good and alsoGood
```

PostgreSQL refuses every further statement of a transaction in which one statement failed; the
savepoint is what makes this possible.

## Options

```kotlin
unitOfWork.transaction(TransactionOptions(isolation = TransactionIsolation.SERIALIZABLE, readOnly = true)) { ... }
unitOfWork.transaction(TransactionOptions(propagation = TransactionPropagation.REQUIRES_NEW)) { ... }
```

| Option | Effect |
|---|---|
| `isolation` | `READ_COMMITTED` (PostgreSQL's default), `REPEATABLE_READ`, `SERIALIZABLE` - of a new transaction |
| `readOnly` | a new transaction that may only read; requesting it inside a running read-write transaction is rejected |
| `REQUIRED` (default) | join the running transaction in a savepoint, or start one |
| `REQUIRES_NEW` | always a new transaction on its own connection, committed or rolled back on its own - e.g. an audit record that must survive the outer rollback |

A joined call inherits the running transaction's isolation level and access mode.

## Rules and pitfalls

- **Keep slow work out.** A transaction holds a pooled connection for its whole duration. Do HTTP
  calls, LLM requests and other slow work before or after the block.
- **One coroutine at a time.** A transaction runs one statement at a time. Running statements of
  one transaction from several coroutines at once (e.g. `async` inside the block) fails with
  `IllegalStateException`.
- **Do not let coroutines outlive it.** A coroutine launched inside the block that is still running
  after the transaction ended fails fast on its next query (`IllegalStateException`) instead of
  using a connection that went back to the pool.
- **`REQUIRES_NEW` needs a second connection** while the outer transaction holds its own. Many
  concurrent callers doing this with a small pool can exhaust it and wait on each other.
- **Cancellation is safe.** When the caller is cancelled, the transaction is rolled back before
  its connection returns to the pool - a pooled connection never carries an open transaction.
- **Listeners cannot break a transaction.** A `QueryExecutionListener` that throws is logged and
  ignored.

## Context propagation

The block runs on the executor's `dispatcher` (default `Dispatchers.Default`) with the caller's
coroutine context, so context elements - e.g. the ambient security identity of
`stratastax-ambient-context` - are visible inside it. In Quarkus, pass a dispatcher that also
propagates the request context (`managedExecutor.asCoroutineDispatcher()`) when code in the block
needs request-scoped beans.

## Batches

`createAll`, `updateAll` and `deleteAll` are not atomic outside a transaction; see
[Batch operations](batch-operations.md#atomicity).
