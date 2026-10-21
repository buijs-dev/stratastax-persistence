# Batch operations

`createAll`, `updateAll` and `deleteAll` write many entities at once. They never throw for one bad
input: the result holds one `BatchItemResult` per input, in input order.

```kotlin
val result: BatchResult<Playset> = playsets.createAll(commands)

result.items.zip(commands).forEach { (item, command) ->
    when (item) {
        is BatchItemResult.Success -> println("created ${item.value.id}")
        is BatchItemResult.Failure -> println("${command.name} failed: ${item.error.message}")
    }
}
result.successes       // the values that succeeded, in input order
result.failures        // the failures, in input order
```

## One round trip per step

The generated access classes do not loop over the inputs: a batch runs the same steps as a single
write, and each step is one `QueryExecutor.batch(sql, paramSets)` - the statement of one row, run
for every input in one round trip (a Vert.x `executeBatch`), with a result per input.

- `createAll`: one batch for the root rows, one per relation, one read-back
  (`SELECT_BY_IDS ... = ANY($1)`). Each input gets the row its own insert returned.
- `updateAll`: one batch of the patch `UPDATE`, each row with its own version check; an input whose
  update returned no row is diagnosed on its own.
- `deleteAll`: one batch of the `DELETE` (or soft-delete `UPDATE`), after locking the rows when the
  entity has relations.

## Outcomes

| Input | Result |
|---|---|
| succeeded | `Success` with what the single call would return |
| unknown id, stale version | `Failure(EntityNotFoundException / OptimisticLockException)` - diagnosed per input |
| the same id twice | the first is written, every repeat is `Failure(IllegalArgumentException)` |
| no expected version for a versioned entity | `Failure(IllegalArgumentException)` |

When the batch as a whole fails - a referenced entity does not exist, a constraint is violated -
nothing of it is kept, and every input is written on its own instead (each in its own transaction,
or a savepoint of the caller's), so each still gets its own outcome (`batchOrEach`). An input
rejected up front keeps its rejection. A batch of one input reports the failure directly.

## Atomicity

Outside a transaction a batch is **not** atomic: inputs that succeed are committed even when others
fail. For all-or-nothing, run it inside a unit of work and throw on the first failure:

```kotlin
unitOfWork.transaction {
    playsets.createAll(commands).orThrow()   // BatchFailedException rolls back the whole unit of work
}
```

`BatchFailedException.failures` holds every failure; the first is the `cause`, the rest are
`suppressed`.

## Cancellation

A batch never records a cancellation as a failed input: a cancelled coroutine stops the batch and
rolls back its transaction. The default implementations (`CreateAccess.createAll` and friends,
one call per input) follow the same rule through `runCatchingNonCancellation` /
`itemResultOf` - use those when you implement a batch yourself.
