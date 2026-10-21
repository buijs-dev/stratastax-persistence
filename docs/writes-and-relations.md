# Writes and relations

This page covers creating, updating and deleting an entity together with its related rows: a game
it references, the expansions it links to, the child rows it owns.

```kotlin
// Example for an entity named Playset
val playset = playsets.create(command)
val renamed = playsets.update(playset.id, UpdatePlayset(displayName = PatchField.Value("Saturday")), expectedVersion = playset.version)
playsets.delete(playset.id, expectedVersion = renamed.version)
```

Every write runs in **one transaction**. Called inside `unitOfWork.transaction { }`, it joins that
transaction instead (see [Transactions](transactions.md)). Either the entity and all its related
rows are written, or nothing is.

The access classes that do this are generated: the statements are constants in `EntitySql`, and
the access class runs them in order. [Code generation](code-generation.md) shows the full files.

## Create
Creating an entity takes up to five steps, each one statement:
1. **Owned rows first.** A row the entity owns through a one-to-one relation 
is inserted before the entity because the entity refers to its key.
2. **The entity itself.** A referenced row is looked up by its public id inside the
   insert.
3. **Links.** The many-to-many links, after checking that every target exists.
4. **Child rows**, and their own children.
5. **Read back.** The new rows are read with the normal read projection, so `create` returns
   exactly what `findById` would.

The access class is just those steps, top to bottom:

```kotlin
// Example for an entity named Playset
private suspend fun insert(sql: QueryExecutor, commands: List<CreatePlayset>): List<Playset> {
    val inserted = sql.batch(PlaysetSql.INSERT, commands.map { mapper.insert(it) })
    val rows = inserted.mapIndexed { index, result -> 
        sql.requireWritten(result, "PLAYSET", mapper.insertReferences(commands[index])) 
    }

    sql.requireAllReferenced("EXPANSION", "PUBLIC_ID", PlaysetSql.EXISTING_EXPANSIONS, expansionIds)
    sql.batch(PlaysetSql.INSERT_EXPANSIONS, links)
    return readBack(sql, rows.map { it.uuid("public_id") })
}
```

### What if a reference does not exist?
A foreign key violation would tell you *that* something is wrong, but not what, and it would abort
the whole transaction. Instead, the insert is guarded: when the reference is missing, it simply writes
nothing.

```sql
INSERT INTO playset (display_name, version, game_id)
SELECT $1::varchar, 1, (SELECT id FROM game WHERE public_id = $2::uuid)
WHERE EXISTS (SELECT 1 FROM game WHERE public_id = $2::uuid)
RETURNING id, public_id
```

`requireWritten` sees that no row came back, checks the references one by one, and throws a
`ReferencedEntityNotFoundException` naming the missing reference. The transaction rolls back. This
extra lookup only happens on the failing path.

## Update
An update is a **patch**: only the fields you set are changed. Each field of an update command is
a `PatchField`:

```kotlin
// Example for an entity named Playset
UpdatePlayset(
    displayName = PatchField.Value("Saturday"),   // set to "Saturday"
    scenario = PatchField.Value(null),            // set to NULL
    variants = PatchField.Unset,                  // leave as it is
)
```

In SQL, every patchable column gets a flag and a value:

```sql
UPDATE playset
SET display_name = CASE WHEN $3::boolean THEN $4::varchar ELSE display_name END,
    scenario     = CASE WHEN $5::boolean THEN $6::varchar ELSE scenario END,
    version = version + 1
WHERE public_id = $1::uuid
  AND version = $2::bigint
  AND deleted_at IS NULL
RETURNING id, public_id
```

The statement text is the same whichever fields are set, so PostgreSQL prepares it once.

When the update matches no row, the access class finds out why: is the row gone
(`EntityNotFoundException`), or did someone else change it first (`OptimisticLockException`)? See
[Versioning and soft delete](versioning-and-soft-delete.md).

### Links
Many-to-many links are **synchronized** with the set in the command. Only the difference is
written: links no longer wanted are removed, missing ones added, the rest left alone.

| Command               | Effect                            |
|-----------------------|-----------------------------------|
| `expansions` unset    | links untouched                   |
| `expansions = {A, B}` | exactly A and B linked afterwards |
| `expansions = {}`     | all links removed                 |

### Child rows
One-to-many children are synchronized too, matched on their id:
- an element whose id already exists is **updated**;
- an element with a new id is **inserted**;
- an existing child that no element mentions anymore is **deleted**, with its own children and
  owned rows.

Some children have no stable identity of their own. A
relation marked `REPLACE_ALL` deletes all existing children first and inserts every element as a
new one.

## Delete

```kotlin
playsets.delete(id, expectedVersion = version)
```

A soft-deletable entity is marked as deleted with one statement (see
[Versioning and soft delete](versioning-and-soft-delete.md)).

A hard delete of an entity with relations cleans up in the right order:
1. lock the rows (`FOR UPDATE`), so nothing changes them halfway;
2. remove the links and child rows;
3. delete the entity's row;
4. delete the rows it owned, which could only go once nothing referred to them anymore.

## Errors
Every exception carries its details as properties (`entityName`, `id`, `expectedVersion`,
`tableName`, `columnName`, `value`), so you can map it to an HTTP response without parsing
messages.

| Exception                           | When                                                                                                 |
|-------------------------------------|------------------------------------------------------------------------------------------------------|
| `EntityNotFoundException`           | updating or deleting an id that does not exist (or is soft-deleted)                                  |
| `OptimisticLockException`           | the expected version is stale                                                                        |
| `ReferencedEntityNotFoundException` | a referenced entity does not exist                                                                   |
| `IllegalArgumentException`          | a versioned entity without an expected version; an id twice in one batch                             |
| `IllegalStateException`             | a written row is not visible when read back (a default condition hides it); the write is rolled back |

## Writes of your own
A handwritten statement joins the caller's transaction like any generated one:

```kotlin
@Language("PostgreSQL")
private const val SOFT_DELETE_PARTICIPANTS = """
    UPDATE match_participant
    SET deleted_at = $2::timestamptz
    WHERE match_id = (SELECT id FROM match WHERE public_id = $1::uuid)"""

unitOfWork.transaction {
    matches.delete(matchId, expectedVersion)
    executor.execute(SOFT_DELETE_PARTICIPANTS, listOf(matchId.value, OffsetDateTime.now(clock)))
}
```

A value-class id is passed as its value (`matchId.value`).

---

Next: [Batch operations](batch-operations.md)
