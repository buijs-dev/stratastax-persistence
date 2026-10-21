# Writes and relations

The `CreateAccess`, `UpdateAccess` and `DeleteAccess` of an entity are generated: every statement
is a `const val` of its `Sql` object, and the access class runs them in order with the write kit
of `persistence-reactive`'s `spi` package. Every write runs in one transaction; called inside
`UnitOfWork.transaction { }` it joins that one (in a savepoint). The examples use the engine's
`playset`: an owner, a game referenced by public id, linked expansions; and the engine's `match`
for one-to-many children with an owned loadout each. [Code generation](code-generation.md) shows
the full generated files.

## Create

```kotlin
private suspend fun insert(
    sql: QueryExecutor,
    commands: List<CreatePlayset>,
): List<Playset> {
    val inserted = sql.batch(PlaysetSql.INSERT, commands.map { mapper.insert(it) })
    val rows =
        inserted.mapIndexed { index, result ->
            sql.requireWritten(result, "PLAYSET", mapper.insertReferences(commands[index]))
        }

    val expansionsLinks = ...                      // (root key, expansion public ids) per command
    sql.requireAllReferenced("EXPANSION", "PUBLIC_ID", PlaysetSql.EXISTING_EXPANSIONS, ...)
    sql.batch(PlaysetSql.INSERT_EXPANSIONS, ...)
    return readBack(sql, rows.map { it.uuid("public_id") })
}
```

What `create` (and `createAll`) does, in one transaction, each step one `batch` for every command:

1. insert the owned one-to-one rows (`INSERT_<RELATION>`); their keys are parameters of the root
   insert;
2. insert the root rows. A reference by public id is a subselect in the insert, guarded so a
   missing referenced row writes nothing:

   ```sql
   INSERT INTO playset (display_name, ..., version, game_id)
   SELECT $1::varchar, ..., 1, (SELECT id FROM game WHERE public_id = $7::uuid)
   WHERE ($7::uuid IS NULL OR EXISTS (SELECT 1 FROM game WHERE public_id = $7::uuid))
   RETURNING id, public_id
   ```

   `requireWritten` turns a missing row into a `ReferencedEntityNotFoundException` for the first
   reference that does not exist (`GAME_EXISTS`), and the transaction rolls back;
3. insert the many-to-many links, after `requireAllReferenced` checked in one query that every
   target named exists;
4. insert the one-to-many children, and their owned rows and nested children;
5. read the rows back with one `SELECT_BY_IDS` and map them with the row mapper, in input order
   (`inKeyOrder`).

## Update

A `PatchField` is two parameters: whether it is set, and its value. An unset field keeps its
value; `PatchField.Value(null)` writes `NULL`.

```sql
UPDATE playset
SET display_name = CASE WHEN $3::boolean THEN $4::varchar ELSE display_name END,
    variants = CASE WHEN $7::boolean THEN $8::text[] ELSE variants END,
    updated_at = $9::timestamptz,
    version = version + 1
WHERE public_id = $1::uuid
  AND version = $2::bigint
  AND deleted_at IS NULL
RETURNING id, public_id
```

An update that matched nothing is explained afterwards: `diagnoseMiss` runs `ACTIVE_ROW` to tell a
missing row (`EntityNotFoundException`) from a stale version (`OptimisticLockException`), and
`requireReferences` finds a patched reference that does not exist.

- **Many-to-many links** are synchronized: `DELETE_<RELATION>_EXCEPT` removes only the links no
  longer wanted and `INSERT_<RELATION>` adds only the missing ones. A command that leaves the field
  unset leaves the links alone; an empty set removes them all.
- **One-to-many children** are synchronized with the command's elements, matched on their
  identity:
  - `<RELATION>_KEYS` reads the existing child keys; `childDiff` splits the elements into the ones
    to update (`UPDATE_<RELATION>`), the ones to insert (`INSERT_<RELATION>_ON_UPDATE`), and the
    child rows no element mentions any more, which are deleted (`DELETE_<RELATION>_BY_IDS`)
    together with their owned rows;
  - a relation marked REPLACE_ALL - such as a match's participants - removes every existing child
    first (`DELETE_<RELATION>_OF`) and inserts every element as a new child: useful when children
    are identified by a natural key that new rows repeat.

## Delete

A soft-deletable entity is deleted with one `SOFT_DELETE` (see
[Versioning and soft delete](versioning-and-soft-delete.md)). A hard delete of an entity with
relations locks the rows first (`LOCK_FOR_DELETE`, `FOR UPDATE`), removes the links and the children
(`DELETE_<RELATION>_OF`), deletes the rows and finally removes their owned rows - and the link
targets, when the entity owns them (`DELETE_<RELATION>_TARGETS`). Rows that reference others go
before the rows they reference.

## Ids

A value-class id binds as its value: the generated mapper unwraps it (`id.value`). The id column is
the column of the entity's identity field, usually `public_id`.

## Errors

| Exception | When |
|---|---|
| `EntityNotFoundException` | an update or delete of an id that does not exist (or is soft-deleted) |
| `OptimisticLockException` | the expected version is stale |
| `ReferencedEntityNotFoundException` | a referenced entity (public id) does not exist |
| `IllegalArgumentException` | a versioned entity without (a valid) expected version; an id twice in one batch |
| `IllegalStateException` | a written row is not visible to the read-back (a default condition hides it) - the write is rolled back |

Every exception carries its details as properties (`entityName`, `id`, `expectedVersion`,
`tableName`, `columnName`, `value`), so a caller can map it to a response.

## Hand-written writes

A write of your own is a statement and its parameters, and joins the caller's transaction like
any other:

```kotlin
@Language("PostgreSQL")
private const val SOFT_DELETE_PARTICIPANTS = """
    UPDATE match_participant
    SET deleted_at = $2::timestamptz
    WHERE match_id = (SELECT id FROM match WHERE public_id = $1::uuid)"""

unitOfWork.transaction {
    deleteAccess.delete(matchId, ifMatch)
    executor.execute(SOFT_DELETE_PARTICIPANTS, listOf(matchId.value, OffsetDateTime.now(clock)))
}
```
