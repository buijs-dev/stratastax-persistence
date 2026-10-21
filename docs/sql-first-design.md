# Why literal SQL
Stratastax Persistence is *SQL-first*: every statement an entity runs is written out as plain SQL
at build time. This page explains why, how the work is split between generated code and the
library, and what that costs.

## The problem
The first version generated configuration objects that described each entity, and the library
turned those into SQL at runtime with jOOQ. It worked, but you could not see what it did. The
participants of a match, for example, were described like this:

```kotlin
OneToManyUpdateWrite(
    shape = OneToManyShape(
        MATCH_PARTICIPANT, MATCH_PARTICIPANT.MATCH_ID, MATCH.ID, MATCH_PARTICIPANT.PUBLIC_ID,
        ownedChildren = listOf(OwnedChildDeleteWrite(MATCH_LOADOUT, MATCH_LOADOUT.ID, MATCH_PARTICIPANT.LOADOUT_ID)),
    ),
    elementFkResolves = listOf(GroupFkResolve(MATCH_PARTICIPANT.GHOST_PLAYER_ID, GHOST_PLAYER.PUBLIC_ID, ...)),
    replaceAll = true,
    ...
)
```

A `Match` had ~1,700 lines of this. To know which SQL ran, you had to read the library's
interpreter, or turn on query logging and reproduce the case.

Yet almost all of that SQL is known at build time. Only the filter, sort, cursor and limit of a
search depend on the request.

## The approach

So the work is turned around:

- **Code generation writes the SQL.** Every statement of an entity becomes a `const val` in one
  file, plus plain Kotlin that runs those statements in order.
- **The library keeps only what is generic:** transactions, running statements and binding their
  parameters, reading rows, rendering a search, and explaining failed writes.

The same participants now read like this:

```kotlin
/** The participants of the matches, and the loadouts they owned. `$1` match ids. */
@Language("PostgreSQL")
const val DELETE_PARTICIPANTS: String =
    "DELETE FROM match_participant WHERE match_id = ANY($1::bigint[]) RETURNING loadout_id"

// in the update access: replace every participant
val loadoutIds = sql.query(MatchSql.DELETE_PARTICIPANTS, listOf(matchIds.toTypedArray())).mapNotNull { it.longOrNull("loadout_id") }
if (loadoutIds.isNotEmpty()) sql.execute(MatchSql.DELETE_LOADOUTS, listOf(loadoutIds.toTypedArray()))
writes.insertParticipants(sql, participants)
```

Open `MatchSql` in the build directory and you see exactly what the database sees.

## Who does what

| Generated per entity                                                  | Library                                                                                 |
|-----------------------------------------------------------------------|-----------------------------------------------------------------------------------------|
| `PlaysetSql`: every statement as a constant                           | `QueryExecutor`: run SQL text with parameters, one statement or a batch                 |
| The search fragments: per field, the column a filter and sort use     | `SearchSql`: add filter, sort, cursor and limit to the fixed `SELECT`                   |
| Row mapper: a row to an entity                                        | `RowContext`: typed access to a row's columns                                           |
| Create and update mappers: a command to the parameters of a statement | Parameter binding: JSON, arrays, enums, timestamps; `patchParams()` for partial updates |
| Access classes: the steps of each write, in order                     | The write kit: check every input's outcome and explain failures                         |
|                                                                       | Transactions, savepoints, listeners                                                     |

## What this buys

- **Readable.** The SQL is in one place per entity, with a comment for every parameter. Your IDE
  checks it against the schema through `@Language("PostgreSQL")`.
- **Predictable.** A statement has the same text on every call, so PostgreSQL prepares it once,
  and it appears as one line in `pg_stat_statements`. What the [test kit](testing.md) records is
  byte for byte the text in the build directory.
- **Smaller library.** The runtime query builder and jOOQ went away. `persistence-reactive`
  shrank from ~4,700 to ~1,850 lines.
- **Plain debugging.** A failed write is a stack trace through ordinary code, not through an
  interpreter of configuration objects.

## What it costs

- **More generated code.** The engine's `Match` is now ~2,000 generated lines instead of ~1,700.
  But every line is either SQL or a step that runs as written.
- **Positional parameters.** `$1, $2, ...` are less self-explanatory than named ones. Every
  statement's KDoc lists its parameters, and every mapper has a comment per parameter to
  compensate.
- **One database.** Literal SQL is written for PostgreSQL. See [SQL dialects](dialects.md).
- **A failing write can cost one or two extra statements.** Rather than checking every reference
  up front, a write runs and is explained afterwards when it matched nothing. The successful path
  never pays for this.

## Techniques worth knowing

A few patterns appear throughout the generated SQL:

**References by public id are subselects, guarded by `EXISTS`.** A missing referenced row makes
the insert write nothing, instead of failing with a constraint error. Only then does the access
class look up which reference was missing.

```sql
INSERT INTO playset (display_name, game_id)
SELECT $1::varchar, (SELECT id FROM game WHERE public_id = $2::uuid)
WHERE EXISTS (SELECT 1 FROM game WHERE public_id = $2::uuid)
RETURNING id, public_id
```

**Partial updates use a flag per column.** An unset field keeps its value:

```sql
SET display_name = CASE WHEN $3::boolean THEN $4::varchar ELSE display_name END
```

**Batches are one statement for one row, run for every input in one round trip** (Vert.x
`executeBatch`). Each input gets its own result, so "matched nothing" is known per input, and the
SQL still reads like hand-written SQL.

**Every parameter has an explicit cast** (`$1::uuid`). PostgreSQL needs it in places like
`$1 IS NULL` or `CASE WHEN`. Casting everywhere is one simple rule, and it documents the type.

**One-to-many relations are read as a JSON array** in the same `SELECT`, so a page never causes
N+1 queries:

```sql
COALESCE((SELECT jsonb_agg(jsonb_build_object('notesId', n.public_id, 'notesText', n.text) ORDER BY n.id)
          FROM playset_note n WHERE n.playset_id = playset.id), '[]'::jsonb) AS notes
```

---

Next: [Reading and search](reading-and-search.md)
