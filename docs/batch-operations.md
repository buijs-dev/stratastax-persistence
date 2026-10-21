# Batch operations

`createAll`, `updateAll` and `deleteAll` write many entities at once. They are built for imports
and bulk edits: fast, because each step is one round trip whatever the number of inputs, and
forgiving, because one bad input does not spoil the rest.

```kotlin
// Example for an entity named Playset
val result: BatchResult<Playset> = playsets.createAll(commands)
result.successes   // the created playsets, in input order
result.failures    // what went wrong, in input order
```

## A result per input
A batch never throws because of one input. Instead, `result.items` holds one `BatchItemResult`
per input, in the same order as the inputs:

```kotlin
result.items.zip(commands).forEach { (item, command) ->
    when (item) {
        is BatchItemResult.Success -> log.info("created ${item.value.id}")
        is BatchItemResult.Failure -> log.warn("${command.displayName} failed: ${item.error.message}")
    }
}
```

| Input                                      | Result                                                                    |
|--------------------------------------------|---------------------------------------------------------------------------|
| written                                    | `Success`, holding what the single call would have returned               |
| unknown id                                 | `Failure(EntityNotFoundException)`                                        |
| stale version                              | `Failure(OptimisticLockException)`                                        |
| the same id twice                          | the first is written; every repeat is `Failure(IllegalArgumentException)` |
| no expected version for a versioned entity | `Failure(IllegalArgumentException)`                                       |

Updates and deletes take a list of items, each with its own id and expected version:

```kotlin
playsets.updateAll(listOf(UpdateBatchItem(id, command, expectedVersion = 3)))
playsets.deleteAll(listOf(DeleteBatchItem(id, expectedVersion = 3)))
```

## Why it is fast
The generated code does not loop over the inputs. A batch runs the same steps as a single write,
and each step sends one statement for every input in a single round trip:

```kotlin
val inserted = sql.batch(PlaysetSql.INSERT, commands.map { mapper.insert(it) })
// one round trip, one result per command
```

Creating 100 records with entity relations is therefore about four round trips, not 400. Each
input still gets its own result back, so a row that was not written is known per input and
explained on its own.

## When the whole batch fails
Some failures cannot be tied to one input: a constraint violation, or a referenced entity that does
not exist. Then nothing of the batch is kept, and the library falls back to writing **every input
on its own**, each in its own transaction (or savepoint). The good inputs are still written, and
the bad ones report their own error.

This is slower, but only happens on the failing path. A batch of a single input just reports the
failure.

## All or nothing
Outside a transaction, a batch is **not** atomic: inputs that succeed are committed even when others
fail. That is usually what an import wants. When it is not, run the batch in a unit of work and
turn the first failure into an exception:

```kotlin
unitOfWork.transaction {
    playsets.createAll(commands).orThrow()   // any failure rolls back everything
}
```

`orThrow()` returns the successful values, or throws a `BatchFailedException`. Its `failures` holds
every failure; the first is also its `cause`, and the rest are added as suppressed exceptions.

## Cancellation
A canceled coroutine is never recorded as a failed input: it stops the batch and rolls back its
transaction. If you implement a batch yourself, use `runCatchingNonCancellation` and `itemResultOf`
from the SPI to follow the same rule; plain `runCatching` would swallow the cancellation.

---

Next: [Versioning and soft delete](versioning-and-soft-delete.md)
