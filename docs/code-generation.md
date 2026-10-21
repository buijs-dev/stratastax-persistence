# Code generation contract

[stratastax-codegen](https://github.com/buijs-dev/stratastax-codegen) generates, per entity, the
classes below on top of the `spi` package of `persistence-reactive`. Every statement is written out
as literal SQL; the generated Kotlin only binds parameters and runs the steps of a write in order.
This page is the contract: what generated code looks like, and what of the library it relies on.

The examples are the engine's `Playset` as generated (a public id, a required game referenced by
public id, an owner from the current principal, a `text[]` of variants and a link table of
expansions).

## Per entity

| File | What it holds |
|---|---|
| `PlaysetSql` | Every statement as a `const val` with `@Language("PostgreSQL")`: the read projection (`COLUMNS`, `FROM`, `SELECT`), `COUNT`, `FIND_BY_ID`, `SELECT_BY_IDS`, a `SELECT_<PROJECTION>` per projection, and the statements of every write |
| `PlaysetEntityModel` | The search field handles (`fields`), the search `configuration`, `searchSql` (per search field the SQL a condition and a sort on it use) and `cursorKeyExtractor` |
| `PlaysetRowMapper` | Reads a row by the aliases of `COLUMNS` |
| `PlaysetCreateMapper`, `PlaysetUpdateMapper` | Per statement a function returning its parameters in placeholder order, a comment per parameter; the rows a statement references by public id; the ids of its links |
| `PlaysetReadAccess`, `PlaysetCreateAccess`, `PlaysetUpdateAccess`, `PlaysetDeleteAccess` | The core `ReadAccess`/`CreateAccess`/`UpdateAccess`/`DeleteAccess` contracts: one statement of `PlaysetSql` per step, for every item of a batch at once, in one transaction |

## The statements

Parameters are numbered placeholders, cast to the column's type as the project's migrations
declare it; each statement's kdoc lists them.

```kotlin
internal object PlaysetSql {

    /**
     * A new row, resolving `game` by public id - and writing nothing when a referenced row does
     * not exist.
     *
     * Parameters: `$1` display_name, `$2` scenario, `$3` variants, `$4` created_at, `$5`
     * public_id, `$6` owner_user_id, `$7` game_id, by game.public_id.
     */
    @Language("PostgreSQL")
    const val INSERT: String = """
        INSERT INTO playset (
            display_name,
            scenario,
            variants,
            created_at,
            public_id,
            owner_user_id,
            version,
            game_id
        )
        SELECT
            $1::varchar,
            $2::varchar,
            $3::text[],
            $4::timestamptz,
            $5::uuid,
            $6::bigint,
            1,
            (SELECT id FROM game WHERE public_id = $7::uuid)
        WHERE ($7::uuid IS NULL OR EXISTS (SELECT 1 FROM game WHERE public_id = $7::uuid))
        RETURNING id, public_id"""

    /**
     * An update: a patched column whose flag is false keeps its value. Matches nothing when the
     * row is gone or its version moved on.
     */
    @Language("PostgreSQL")
    const val UPDATE: String = """
        UPDATE playset
        SET display_name = CASE WHEN $3::boolean THEN $4::varchar ELSE display_name END,
            scenario = CASE WHEN $5::boolean THEN $6::varchar ELSE scenario END,
            variants = CASE WHEN $7::boolean THEN $8::text[] ELSE variants END,
            updated_at = $9::timestamptz,
            version = version + 1
        WHERE public_id = $1::uuid
          AND version = $2::bigint
          AND deleted_at IS NULL
        RETURNING id, public_id"""
}
```

| Shape | Statements |
|---|---|
| Root | `INSERT` (a reference by public id resolved by subselect, guarded so a missing one writes nothing), `UPDATE` (a `PatchField` is a flag and a value: `CASE WHEN $n THEN ... ELSE col END`), `SOFT_DELETE` or `DELETE` (with relations: `LOCK_FOR_DELETE`, then `DELETE` by key), `ACTIVE_ROW` and `<TABLE>_EXISTS` to explain a write that matched nothing |
| One-to-one, referenced | resolved inside the root statement, checked by `<TABLE>_EXISTS` |
| One-to-one, owned | `INSERT_<G>` before the root row (its key is a parameter of the root insert), `DELETE_<G>` after the rows that referenced it |
| Many-to-many | `EXISTING_<G>` (every target named must exist), `INSERT_<G>` (only links not there yet), `DELETE_<G>_EXCEPT` on update, `DELETE_<G>_OF` on delete, `DELETE_<G>_TARGETS` when owned |
| One-to-many, managed | `INSERT_<G>` / `INSERT_<G>_ON_UPDATE`, `UPDATE_<G>`, `<G>_KEYS`, `DELETE_<G>_OF`, `DELETE_<G>_BY_IDS`; nested relations the same, keyed by their parent element |

The version is literal SQL: a new row starts at `1`, an update or soft delete sets
`version = version + 1` and matches only the expected version.

## The mappers

```kotlin
@Singleton
internal class PlaysetCreateMapper(
    private val playsetIdGenerator: PlaysetIdGenerator,
    private val standardOffsetDateTimeGenerator: StandardOffsetDateTimeGenerator,
    private val getCurrentPrincipalId: GetCurrentPrincipalId,
) {
    /** The parameters of [PlaysetSql.INSERT]. */
    fun insert(command: CreatePlayset): List<Any?> =
        listOf(
            command.displayName, // $1 display_name
            command.scenario, // $2 scenario
            command.variants.toSqlArray(), // $3 variants
            standardOffsetDateTimeGenerator.generate(), // $4 created_at
            playsetIdGenerator.generate().value, // $5 public_id
            getCurrentPrincipalId.generate(), // $6 owner_user_id
            command.gameId.value, // $7 game_id, by game.public_id
        )

    /** The rows [PlaysetSql.INSERT] references by public id. */
    fun insertReferences(command: CreatePlayset): List<Reference> =
        listOf(
            Reference("GAME", "PUBLIC_ID", PlaysetSql.GAME_EXISTS, command.gameId.value),
        )

    fun expansions(command: CreatePlayset): Set<UUID>? = command.expansions.map { it.value }.toSet()
}
```

A patched field is two parameters, whether it is set and its value:

```kotlin
    fun update(
        command: UpdatePlayset,
        id: PlaysetId,
        expectedVersion: Any?,
    ): List<Any?> =
        buildList {
            add(id.value) // $1 public_id
            add(expectedVersion) // $2 version
            addAll(command.displayName.patchParams()) // $3, $4 display_name
            addAll(command.scenario.patchParams()) // $5, $6 scenario
            addAll(command.variants.nullAs(emptySet()).patchParams { it.toSqlArray() }) // $7, $8 variants
            add(standardOffsetDateTimeGenerator.generate()) // $9 updated_at
        }
```

## The access classes

The steps of a write are plain code, top to bottom:

```kotlin
    private suspend fun insert(
        sql: QueryExecutor,
        commands: List<CreatePlayset>,
    ): List<Playset> {
        val inserted =
            sql.batch(
                PlaysetSql.INSERT,
                commands.map { mapper.insert(it) },
            )
        val rows =
            inserted.mapIndexed { index, result ->
                sql.requireWritten(result, "PLAYSET", mapper.insertReferences(commands[index]))
            }

        val expansionsLinks =
            commands.indices.mapNotNull { index ->
                val key = rows[index].long("id")
                mapper.expansions(commands[index])?.let { key to it }
            }

        sql.requireAllReferenced(
            "EXPANSION",
            "PUBLIC_ID",
            PlaysetSql.EXISTING_EXPANSIONS,
            expansionsLinks.flatMap { it.second }.distinct().toTypedArray(),
        )
        sql.batch(
            PlaysetSql.INSERT_EXPANSIONS,
            expansionsLinks
                .filter { it.second.isNotEmpty() }
                .map { (key, ids) -> listOf(key, ids.toTypedArray()) },
        )
        return readBack(sql, rows.map { it.uuid("public_id") })
    }
```

A read is one statement over the generated fragments:

```kotlin
    override suspend fun findById(id: PlaysetId): Playset? =
        queryExecutor.queryFirst(PlaysetSql.FIND_BY_ID, listOf(id.value))?.let(rowMapper::map)

    override suspend fun findAll(search: SearchQuery): Page<Playset> =
        queryExecutor.readPage(PlaysetEntityModel.searchSql.pageQuery(search), search, rowMapper)
```

`findAll` and `count` also take a scope, a `SearchQueryBuilder` block or a `SearchRequest` (cursor
pages); `findAllForUpdate` locks the rows of a page until the transaction ends; every projection
gets `findAll<P>` and `findAll<P>List`, selecting only its own columns.

## What generated code uses of the library

All in `persistence-reactive`'s `spi` package:

| | |
|---|---|
| Running statements | `QueryExecutor.query` / `queryFirst` / `execute` / `batch(sql, paramSets)` (a batch is one round trip, a result per parameter set), `TransactionalExecutor.withTransaction` |
| Search | `SearchSql`, `SearchField`, `SearchTarget`, `SqlType`; `pageQuery` / `listQuery` / `lockQuery` / `countQuery` render a search's conditions, sort, keyset seek and limit over them; `readPage` / `readAll` |
| Writes | `batchOrEach` (a batch, or every item on its own when it fails as a whole), `rejectedInputs`, `SqlVersion`, `diagnoseMiss`, `Reference`, `requireReferences`, `requireWritten`, `requireAllReferenced`, `childDiff`, `inKeyOrder` |
| Parameters | `PatchField.patchParams()`, `toSqlArray()`; the executor binds a `Map` as JSON, a `URI`/`URL` as text, an enum by name, an `Instant` as a timestamp |

## Hand-written SQL next to it

Hand-written queries reuse the generated fragments rather than repeating the projection:

```kotlin
@Language("PostgreSQL")
private const val FIND_BY_PROVIDER = """
    SELECT ${UserSql.COLUMNS}, app_user.id AS $ENTITY_ID_ALIAS
    FROM ${UserSql.FROM}
    JOIN app_user_identity identity ON app_user.id = identity.user_id
    WHERE identity.provider = $1::varchar AND identity.provider_user_id = $2::varchar"""

executor.queryFirst(FIND_BY_PROVIDER, listOf(provider, providerUserId))?.let(mapper::map)
```

## Rules the generated code relies on

- A column's alias in `COLUMNS` is the name of the field its row mapper reads; a one-to-many is one
  aggregated JSON column under the group's name, its elements keyed by their field names. Aliases
  are unquoted, so Postgres folds them to lowercase; a row context finds a column the way the
  dialect folds its name.
- A mapper function returns exactly the parameters of its statement, in placeholder order.
- An array parameter is a typed array (`Array<UUID>`, also when empty): Postgres checks its type.
- A value class binds as its value: the mapper unwraps it.
- A batch statement writes or matches one row per parameter set and returns it (`RETURNING`); no
  row means not found, a stale version, or a missing reference, which the access class explains
  afterwards with `diagnoseMiss` and `requireReferences`.
- Owned rows are inserted before the rows that reference them and deleted after them; links and
  children of a deleted row go before it.
- Written rows are read back with one `SELECT_BY_IDS` and returned in input order (`inKeyOrder`).
- Generated sources are written the way the stratastax style formats them, so a consumer's
  formatter leaves them as they are.
