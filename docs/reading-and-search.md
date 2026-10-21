# Reading and search

## The read projection

An entity is read with one literal `SELECT`: its columns, each aliased to the name of the field its
row mapper reads, and its one-to-one relations as joins. This is the engine's `Playset` as
[code generation](code-generation.md) writes it:

```kotlin
internal object PlaysetSql {

    /** The read projection's columns, each named after the field its row mapper reads. */
    const val COLUMNS: String = """
        playset.public_id AS id,
        playset.display_name AS name,
        playset.variants AS variants,
        playset.created_at AS createdAt,
        playset.version AS version,
        game.public_id AS gameId,
        game.display_name AS gameName,
        owner.public_id AS ownerId,
        COALESCE((
            SELECT jsonb_agg(jsonb_build_object(
                'expansionsId', expansions_m.public_id,
                'expansionsName', expansions_m.display_name
            ) ORDER BY expansions_m.id)
            FROM playset_expansion expansions_mj
            JOIN expansion expansions_m ON expansions_mj.expansion_id = expansions_m.id
            WHERE playset.id = expansions_mj.playset_id
        ), '[]'::jsonb) AS expansions"""

    /** The table and its one-to-one relations, each joined under the relation's name. */
    const val FROM: String = """
        playset
        JOIN game ON playset.game_id = game.id
        JOIN app_user owner ON playset.owner_user_id = owner.id"""

    @Language("PostgreSQL")
    const val SELECT: String = """
        SELECT $COLUMNS
        FROM $FROM"""
}
```

| Relation | Read as |
|---|---|
| one-to-one | a join of the related table under the relation's name; a `LEFT JOIN` when the foreign key is nullable (or a parent relation is optional), so a missing relation leaves its columns `NULL` |
| one-to-many | one JSON array column named after the relation, ordered by the child table's primary key |
| many-to-many | the same, through the link table |

One-to-many relations nest: the array of a child holds the arrays of its own children. A related
table is always aliased, so a relation to the same table - `category.parent` - works as expected.
Related rows are read in the same statement, so a page never causes N+1 queries.

## Row mappers

A `RowMapper` reads a row by those aliases:

```kotlin
val playsetMapper =
    RowMapper { row ->
        Playset(
            id = row.uuid("id"),
            name = row.string("name"),
            gameName = row.stringOrNull("gameName"),           // null without a game
            expansions = row.objectList("expansions").map { Expansion(it.uuid("expansionsId"), it.string("expansionsName")) },
        )
    }
```

`RowContext` has the same accessors for every type: `x(name)` (fails on NULL), `xOrNull(name)`,
`xList(name)` and `xSet(name)`. Reading a column the row does not have throws
`UnknownColumnException`: it never silently reads as `null`.

## Searching

A search is rendered over the entity's `SearchSql`: the read projection and the count as they are,
and per searchable field the SQL type, where a condition on it goes and the column a sort orders
by.

```kotlin
val searchSql: SearchSql =
    SearchSql(
        select = PlaysetSql.SELECT,
        countSelect = PlaysetSql.COUNT,
        fields =
            mapOf(
                "id" to SearchField(SqlType.UUID, SearchTarget.Column("playset.public_id"), sortColumn = "playset.public_id", neverNull = true),
                "name" to SearchField(SqlType.VARCHAR, SearchTarget.Column("playset.display_name"), sortColumn = "playset.display_name"),
                "gameName" to
                    SearchField(
                        type = SqlType.VARCHAR,
                        target =
                            SearchTarget.Related(
                                exists = "EXISTS (SELECT 1 FROM game game_x WHERE playset.game_id = game_x.id AND {condition})",
                                column = "game_x.display_name",
                                absent = "NOT EXISTS (SELECT 1 FROM game game_x WHERE playset.game_id = game_x.id)",
                            ),
                        sortColumn = "game.display_name",
                    ),
            ),
        defaultConditions = listOf("playset.deleted_at IS NULL"),
        lockClause = "FOR UPDATE OF playset",
    )
```

A generated `ReadAccess` runs it:

```kotlin
override suspend fun findById(id: PlaysetId): Playset? =
    queryExecutor.queryFirst(PlaysetSql.FIND_BY_ID, listOf(id.value))?.let(rowMapper::map)

override suspend fun findAll(search: SearchQuery): Page<Playset> =
    queryExecutor.readPage(PlaysetEntityModel.searchSql.pageQuery(search), search, rowMapper)
```

`count` runs `countQuery`: the conditions over `SELECT count(*) FROM playset`, without joins, and
ignoring the limit, the sort and the cursor. Usually the search layer (`search-core`'s
`SearchParser` and query DSL) builds the `SearchQuery`; the generated access class also takes a
`SearchRequest` or a builder block and returns cursor pages.

- **Filters**: `=`, `<>`, `<`, `<=`, `>`, `>=`, `LIKE`, `ILIKE`, `IN`, `OUT`, `AND`, `OR`. `NULL`
  is a value of its own: `field = null` is `IS NULL`, `IN (a, null)` matches `a` and `NULL`, `OUT
  (null)` excludes `NULL`. A condition on a related field is an `EXISTS (...)` along the relation:
  a filter on a one-to-many field matches a root row once however many children match, and a
  condition that matches `NULL` also matches a row without the one-to-one relation. Several values
  of a UUID, text or integer field bind as one array (`= ANY($1::uuid[])`).
- **Sorting**: NULL sorts as the largest value (`ASC NULLS LAST`, `DESC NULLS FIRST`). Fields of
  a one-to-many relation cannot be sorted on.
- **Keyset paging**: pass the cursor of the last row (`CursorDirection.AFTER`) or of the first row
  (`BEFORE`). A page reads one row more than its limit to set `hasMore`. When every sort field is
  `neverNull` and sorted the same way, the seek is one row comparison - `(playset.display_name,
  playset.public_id) > ($1, $2)` - that an index on those columns serves directly.
- **Default conditions** apply to every read, including `findById` and the read-back after a
  write: a soft-deleted row is never read.
- **Scope**: `findAll(query, scope)` and `count(query, scope)` restrict the search to a
  `FilterExpression` on top of the query's filter - what the caller may see, e.g.
  `ownerIdField eq currentUser`. The scope is not part of the `SearchQuery`, so the cursors
  `CursorPageAssembler` builds for that query only carry the caller's own sort and filter: a client
  that sends those again with the next cursor matches it, and the scope is applied anew on every
  request.

A sort, cursor or value that does not fit fails with a `SearchException`, and a field the entity
does not have with an `UnknownFieldException`, before any SQL runs.

## Projections

A projection - another type with some of the fields, such as `MatchRef` - gets a `SELECT` of its
own: only its columns, and only the joins a sort on a relation needs. Its access methods render the
search over that statement instead of the full projection:

```kotlin
val refs: Page<MatchRef> = matches.findAllMatchRef { limit = 20 }
val all: List<MatchRef> = matches.findAllMatchRefList { ... }    // ignores the limit and the cursor
```

## Work queues: `findAllForUpdate`

```kotlin
unitOfWork.transaction {
    val claimed = playsets.findAllForUpdate(nextBatch)       // FOR UPDATE OF playset SKIP LOCKED
    claimed.forEach { process(it) }
}
```

The returned root rows stay locked until the transaction ends; rows another worker has locked are
skipped, so concurrent workers each claim different rows (`skipLocked = false` waits for them
instead). It must run inside a transaction - outside one it throws, because the locks would be gone
right away.

## Hand-written queries

A query of your own reuses the generated fragments rather than repeating the projection, and maps
its rows with the generated row mapper:

```kotlin
@Language("PostgreSQL")
private const val FIND_BY_PROVIDER = """
    SELECT ${UserSql.COLUMNS}
    FROM ${UserSql.FROM}
    JOIN app_user_identity identity ON app_user.id = identity.user_id
    WHERE identity.provider = $1::varchar AND identity.provider_user_id = $2::varchar"""

executor.queryFirst(FIND_BY_PROVIDER, listOf(provider, providerUserId))?.let(mapper::map)
```
