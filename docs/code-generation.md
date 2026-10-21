# Code generation

You rarely write persistence code by hand. [stratastax-codegen](https://github.com/buijs-dev/stratastax-codegen)
reads your annotated entities and the Flyway migrations, and generates the SQL, the mappers and the
access classes for each entity on top of this library.

This page shows what that generated code looks like, so you can read it when debugging, and lists
the rules it relies on, so a handwritten class can follow them too.

The example is the engine's `Playset`: a required game referenced by public id, an owner taken from
the current user, a `text[]` column of variants, and linked expansions.

## What is generated

| File                                                                                     | What it holds                                                    |
|------------------------------------------------------------------------------------------|------------------------------------------------------------------|
| `PlaysetSql`                                                                             | every statement of the entity, as a string constant              |
| `PlaysetEntityModel`                                                                     | the search configuration, and `searchSql` for rendering searches |
| `PlaysetRowMapper`                                                                       | a database row to a `Playset`                                    |
| `PlaysetCreateMapper`, `PlaysetUpdateMapper`                                             | a command to the parameters of a statement                       |
| `PlaysetReadAccess`, `PlaysetCreateAccess`, `PlaysetUpdateAccess`, `PlaysetDeleteAccess` | the `ReadAccess`, `CreateAccess`, ... a service injects          |

Everything lands in the build directory. When you want to know what an entity does in the
database, open its `Sql` object.

## The statements

Each statement is a constant with `@Language("PostgreSQL")`, so the IDE highlights and checks it.
Its KDoc lists the parameters:

```kotlin
internal object PlaysetSql {

    /**
     * A new row, resolving `game` by public id and writing nothing when it does not exist.
     *
     * Parameters: `$1` display_name, `$2` scenario, `$3` variants, `$4` created_at, `$5` public_id,
     * `$6` owner_user_id, `$7` game_id, by game.public_id.
     */
    @Language("PostgreSQL")
    const val INSERT: String = """
        INSERT INTO playset (display_name, scenario, variants, created_at, public_id, owner_user_id, version, game_id)
        SELECT $1::varchar, $2::varchar, $3::text[], $4::timestamptz, $5::uuid, $6::bigint, 1,
               (SELECT id FROM game WHERE public_id = $7::uuid)
        WHERE ($7::uuid IS NULL OR EXISTS (SELECT 1 FROM game WHERE public_id = $7::uuid))
        RETURNING id, public_id"""
}
```

Which statements an entity gets depends on its relations:

| Shape                   | Statements                                                                                                                                                                      |
|-------------------------|---------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| the entity              | `SELECT`, `FIND_BY_ID`, `SELECT_BY_IDS`, `COUNT`, `INSERT`, `UPDATE`, `DELETE` or `SOFT_DELETE`, plus `ACTIVE_ROW` and `<TABLE>_EXISTS` to explain a write that matched nothing |
| a referenced one-to-one | resolved inside the entity's own statements                                                                                                                                     |
| an owned one-to-one     | `INSERT_<G>` before the entity, `DELETE_<G>` after it                                                                                                                           |
| many-to-many            | `EXISTING_<G>`, `INSERT_<G>`, `DELETE_<G>_EXCEPT`, `DELETE_<G>_OF`, and `DELETE_<G>_TARGETS` when the entity owns the targets                                                   |
| one-to-many             | `INSERT_<G>`, `UPDATE_<G>`, `<G>_KEYS`, `DELETE_<G>_OF`, `DELETE_<G>_BY_IDS`; nested children the same way                                                                      |
| a projection            | `SELECT_<PROJECTION>` with only its own columns                                                                                                                                 |

## The mappers
A mapper function returns the parameters of one statement, in placeholder order, with a comment for
each one. Generated values, like a new id or the current user, are filled in here:

```kotlin
fun insert(command: CreatePlayset): List<Any?> =
    listOf(
        command.displayName,                          // $1 display_name
        command.scenario,                             // $2 scenario
        command.variants.toSqlArray(),                // $3 variants
        standardOffsetDateTimeGenerator.generate(),   // $4 created_at
        playsetIdGenerator.generate().value,          // $5 public_id
        getCurrentPrincipalId.generate(),             // $6 owner_user_id
        command.gameId.value,                         // $7 game_id, by game.public_id
    )
```

A patched field of an update is two parameters, whether it is set and its value:

```kotlin
addAll(command.displayName.patchParams())   // $3, $4 display_name
```

## The access classes
A write is its steps, top to bottom, each one statement for every input at once:

```kotlin
private suspend fun insert(sql: QueryExecutor, commands: List<CreatePlayset>): List<Playset> {
    val inserted = sql.batch(PlaysetSql.INSERT, commands.map { mapper.insert(it) })
    val rows = inserted.mapIndexed { index, result -> sql.requireWritten(result, "PLAYSET", mapper.insertReferences(commands[index])) }
    sql.requireAllReferenced("EXPANSION", "PUBLIC_ID", PlaysetSql.EXISTING_EXPANSIONS, expansionIds)
    sql.batch(PlaysetSql.INSERT_EXPANSIONS, links)
    return readBack(sql, rows.map { it.uuid("public_id") })
}
```

A read is one line:

```kotlin
override suspend fun findById(id: PlaysetId): Playset? =
    queryExecutor.queryFirst(PlaysetSql.FIND_BY_ID, listOf(id.value))?.let(rowMapper::map)

override suspend fun findAll(search: SearchQuery): Page<Playset> =
    queryExecutor.readPage(PlaysetEntityModel.searchSql.pageQuery(search), search, rowMapper)
```

Besides the `ReadAccess` contract, the generated read access also offers `findAll` and `count`
with a scope, with a search builder block or with a `SearchRequest` (returning cursor pages),
`findAllForUpdate`, and `findAll<P>` / `findAll<P>List` for every projection.

## What it uses of the library
All from the `spi` package of `persistence-reactive`:

| For                |                                                                                                                                                                      |
|--------------------|----------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| running statements | `QueryExecutor.query`, `queryFirst`, `execute`, `batch`; `TransactionalExecutor.withTransaction`                                                                     |
| searching          | `SearchSql`, `SearchField`, `SearchTarget`, `SqlType`; `pageQuery`, `listQuery`, `lockQuery`, `countQuery`; `readPage`, `readAll`                                    |
| writing            | `batchOrEach`, `rejectedInputs`, `SqlVersion`, `diagnoseMiss`, `Reference`, `requireReferences`, `requireWritten`, `requireAllReferenced`, `childDiff`, `inKeyOrder` |
| parameters         | `patchParams()`, `toSqlArray()`                                                                                                                                      |

The executor converts common types when binding: a `Map` to JSON, a `URI` or `URL` to text, an
enum to its name, an `Instant` to a timestamp.

## The rules
Generated code, and any handwritten code next to it, relies on these:
- **Aliases are field names.** Each column in `COLUMNS` is aliased to the field its row mapper
  reads. A one-to-many relation is one JSON column named after the relation. Aliases are unquoted,
  so PostgreSQL lowercases them; the row context still finds `createdAt` as `createdat`.
- **Parameters in placeholder order.** A mapper returns exactly the parameters of its statement, in
  order.
- **Arrays are typed.** An array parameter is a typed array, such as `Array<UUID>`, also when empty.
  PostgreSQL checks its type, so pass `ids.toTypedArray()` or `toSqlArray()`, not a `List`.
- **Value classes are unwrapped.** The mapper passes `id.value`, not `id`.
- **A batch statement handles one row per parameter set and returns it** (`RETURNING`). No row
  back means not found, a stale version or a missing reference, which the access class explains
  afterwards.
- **Owned rows go in first and out last.** Links and children of a deleted row go before it.
- **Writes return what a read returns.** Written rows are read back with one `SELECT_BY_IDS`, in
  input order.
- **Generated code is already formatted** in the Stratastax style, so a consumer's formatter leaves
  it alone.

---

Next: [SQL dialects](dialects.md)
