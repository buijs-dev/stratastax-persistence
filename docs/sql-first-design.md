# SQL-first persistence (design)

Status: **done** (2026-10-21). Codegen writes the SQL-first shape for every entity, the jOOQ-built
route is gone from the library, and jOOQ is gone from the whole chain - library, code generation,
Quarkus starter, http stores, engine and game catalog. See [Phases 3 and 4](#phases-3-and-4).

## Why

The generated persistence code of an application is hard to read. A `Match` gets ~1,700 lines of
configuration objects - `MatchEntityModel` alone is 824 lines of `JooqField`, `OneToOneGroup`,
`JunctionGroup`, and the access classes are nested `OneToManyUpdateWrite(shape =
OneToManyShape(...), elementOwnedChildCreateWrites = listOf(OwnedChildCreateWrite(...)))`. The
library interprets those objects at runtime and builds the SQL with jOOQ, so the SQL that actually
runs appears nowhere in the code.

Almost all of that SQL is known at build time. Only the filter, sort, cursor and limit of a search
depend on the request. Of `persistence-reactive`'s ~4,700 lines (without license headers), ~3,500
are this runtime query building.

**SQL-first** turns this around:

- Code generation writes **the literal SQL of every statement** of an entity into the build
  directory, plus **plain sequential Kotlin** that runs those statements.
- The library only keeps what is generic: transactions, running SQL and binding parameters, row
  access, rendering the search fragments, and the checks and bookkeeping of a write.

## Who does what

| Generated per entity (literal) | Library (generic, small) |
|---|---|
| `PlaysetSql`: every statement as a `const val` with `@Language("PostgreSQL")` - read projection, `findById`, count, insert, patch update, (soft) delete, child and link statements, read-back, the checks that explain a miss | `QueryExecutor.query` / `queryFirst` / `execute` / `batch` on SQL text and parameters (Vert.x `preparedQuery`, `executeBatch`) |
| `PlaysetSearchSql`: per search field the SQL a condition and a sort use (column or `EXISTS` template, type, NOT NULL) | `pageQuery` / `listQuery` / `lockQuery` / `countQuery`: filter to `WHERE`, sort to `ORDER BY`, keyset seek, limit, parameters; `readPage` / `readAll` |
| Row mapper (unchanged: reads columns by alias) | `VertxTransactionalExecutor`, `AmbientQueryExecutor`, savepoints, listeners (unchanged) |
| Create and update mappers: a command to the parameters of a statement, in placeholder order | Parameter binding: a `Map` as a JSON object, typed arrays as SQL arrays; `PatchField.patchParams()` |
| Access classes: the steps of a write, explicitly (owned rows, root rows, links, children, read-back) | Write kit: `batchOrEach`, `rejectedInputs`, `SqlVersion`, `diagnoseMiss`, `requireReferences`, `requireWritten`, `requireAllReferenced`, `childDiff`, `inKeyOrder` |
| Entity model: only `fields`, `configuration`, `cursorKeyExtractor` (search-core) | Exceptions, `BatchResult`, `RowContext`, `RowMapper` (core, unchanged) |

The library API this needs is in `persistence-reactive`'s `spi` package: [`SearchSql.kt`](../persistence-reactive/src/main/kotlin/dev/buijs/stratastax/persistence/reactive/spi/SearchSql.kt),
[`SqlWrites.kt`](../persistence-reactive/src/main/kotlin/dev/buijs/stratastax/persistence/reactive/spi/SqlWrites.kt)
and the SQL methods of [`QueryExecutor`](../persistence-reactive/src/main/kotlin/dev/buijs/stratastax/persistence/reactive/spi/Executors.kt).

## What generated code looks like

The PoC writes, by hand, what code generation would produce for the test `playset`: every kind of
relation (optional and required one-to-one, an owned one-to-one, a link table, soft-deletable and
versioned one-to-many notes with nested reactions, and a self-reference in `category`). See
[`persistence-reactive/src/test/.../sqlfirst/`](../persistence-reactive/src/test/kotlin/dev/buijs/stratastax/persistence/reactive/sqlfirst).

### The statements

```kotlin
internal object PlaysetSql {

    const val COLUMNS: String = """
        playset.public_id AS id,
        playset.name AS name,
        game.name AS gameName,
        owner.display_name AS ownerDisplayName,
        COALESCE((
            SELECT jsonb_agg(jsonb_build_object(
                'notesId', notes_m.public_id,
                'notesText', notes_m.text,
                ...
            ) ORDER BY notes_m.id)
            FROM playset_note notes_m
            LEFT JOIN game notesGame_m ON notes_m.game_id = notesGame_m.id
            WHERE playset.id = notes_m.playset_id AND notes_m.deleted_at IS NULL
        ), '[]'::jsonb) AS notes"""

    const val FROM: String = """
        playset
        LEFT JOIN game ON playset.game_id = game.id
        JOIN app_user owner ON playset.owner_id = owner.id
        LEFT JOIN loadout ON playset.loadout_id = loadout.id"""

    @Language("PostgreSQL")
    const val SELECT: String = "SELECT $COLUMNS\nFROM $FROM"

    /**
     * A playset, resolving its game and owner by public id - and inserting nothing when one of them
     * does not exist. `$1` public id, `$2` name, `$3` attributes, `$4` loadout id, `$5` game public
     * id, `$6` owner public id.
     */
    @Language("PostgreSQL")
    const val INSERT: String = """
        INSERT INTO playset (public_id, name, attributes, loadout_id, version, game_id, owner_id)
        SELECT $1::uuid, $2::varchar, $3::jsonb, $4::bigint, 1,
               (SELECT id FROM game WHERE public_id = $5::uuid),
               (SELECT id FROM app_user WHERE public_id = $6::uuid)
        WHERE ($5::uuid IS NULL OR EXISTS (SELECT 1 FROM game WHERE public_id = $5::uuid))
          AND EXISTS (SELECT 1 FROM app_user WHERE public_id = $6::uuid)
        RETURNING id, public_id"""

    /**
     * A patch: a column whose flag is false keeps its value. `$1` public id, `$2` expected version,
     * `$3`/`$4` name, `$5`/`$6` attributes, `$7`/`$8` game public id.
     */
    @Language("PostgreSQL")
    const val UPDATE: String = """
        UPDATE playset
        SET name = CASE WHEN $3::boolean THEN $4::varchar ELSE name END,
            attributes = CASE WHEN $5::boolean THEN $6::jsonb ELSE attributes END,
            game_id = CASE WHEN $7::boolean THEN (SELECT id FROM game WHERE public_id = $8::uuid) ELSE game_id END,
            version = version + 1
        WHERE public_id = $1::uuid
          AND version = $2::bigint
          AND deleted_at IS NULL
          AND (NOT $7::boolean OR $8::uuid IS NULL OR EXISTS (SELECT 1 FROM game WHERE public_id = $8::uuid))
        RETURNING id, public_id"""
}
```

### The mappers

```kotlin
internal class PlaysetCreateMapper {

    /** [PlaysetSql.INSERT]. */
    fun insert(command: CreatePlayset, loadoutId: Long?): List<Any?> =
        listOf(
            command.id, // $1 public_id
            command.name, // $2 name
            command.attributesJson?.let(::JsonObject), // $3 attributes
            loadoutId, // $4 loadout_id
            command.gameId, // $5 game_id, by game.public_id
            command.ownerId, // $6 owner_id, by app_user.public_id
        )
}

internal class PlaysetUpdateMapper {

    /** [PlaysetSql.UPDATE]: per patched column whether it is set, and its value. */
    fun update(id: UUID, expectedVersion: Any?, command: UpdatePlayset): List<Any?> =
        listOf(id, expectedVersion) + // $1 public_id, $2 version
            command.name.patchParams() + // $3, $4 name
            command.attributesJson.patchParams { JsonObject(it) } + // $5, $6 attributes
            command.gameId.patchParams() // $7, $8 game_id, by game.public_id
}
```

### The access classes

The steps of a write are plain code, one statement per step for every input of a batch at once:

```kotlin
private suspend fun insert(sql: QueryExecutor, commands: List<CreatePlayset>): List<PlaysetEntity> {
    val loadoutIds = writes.insertLoadouts(sql, commands.map { it.loadout })
    val inserted = sql.batch(PlaysetSql.INSERT, commands.mapIndexed { index, command -> mapper.insert(command, loadoutIds[index]) })
    val playsetIds = inserted.mapIndexed { index, rows -> sql.requireWritten(rows, TABLE, mapper.references(commands[index])).long("id") }

    val links = commands.indices.mapNotNull { index -> commands[index].expansionIds?.let { playsetIds[index] to it } }
    writes.requireExpansions(sql, links)
    writes.insertExpansions(sql, links)

    val notes = commands.indices.flatMap { index -> commands[index].notes.map { it to playsetIds[index] } }
    writes.insertNotes(sql, notes)
    writes.insertReactions(sql, notes.map { it.first })
    return writes.readBack(sql, commands.map { it.id })
}
```

A read is one line over the generated fragments:

```kotlin
override suspend fun findAll(search: SearchQuery): Page<PlaysetEntity> =
    queries.readPage(PlaysetSearchSql.sql.pageQuery(search), search, rowMapper)

override suspend fun findById(id: UUID): PlaysetEntity? =
    queries.queryFirst(PlaysetSql.FIND_BY_ID, listOf(id))?.let(rowMapper::map)
```

### Before and after: `Match` in the engine

Today the participants of a match are configuration that the library interprets:

```kotlin
override val updateOneToManyWrites: List<OneToManyUpdateWrite<UpdateMatch, *>> =
    listOf(
        OneToManyUpdateWrite(
            shape = OneToManyShape(
                MATCH_PARTICIPANT, MATCH_PARTICIPANT.MATCH_ID, MATCH.ID, MATCH_PARTICIPANT.PUBLIC_ID,
                ownedChildren = listOf(OwnedChildDeleteWrite(MATCH_LOADOUT, MATCH_LOADOUT.ID, MATCH_PARTICIPANT.LOADOUT_ID)),
            ),
            elements = { command -> command.participants.orNullIfUnset() },
            elementId = { _: CreateMatchParticipant -> matchParticipantIdGenerator.generate().value },
            elementMapper = UpdateMapper<CreateMatchParticipant, UpdateValues> { command -> UpdateValues(...) },
            elementFkResolves = listOf(GroupFkResolve(MATCH_PARTICIPANT.GHOST_PLAYER_ID, GHOST_PLAYER.PUBLIC_ID, GHOST_PLAYER.ID, { it.ghostPlayerId.value }), ...),
            replaceAll = true,
            elementOwnedChildCreateWrites = listOf(OwnedChildCreateWrite(...)),
            version = ChildVersion(MATCH_PARTICIPANT.VERSION, initialVersionStrategy),
        ),
    )
```

SQL-first, the same relation (`REPLACE_ALL`, owned loadouts) is literal SQL and three steps:

```kotlin
/** The participants of the matches, and the loadouts they owned. `$1` match ids. */
@Language("PostgreSQL")
const val DELETE_PARTICIPANTS: String =
    "DELETE FROM match_participant WHERE match_id = ANY($1::bigint[]) RETURNING loadout_id"

/** `$1` loadout ids. */
@Language("PostgreSQL")
const val DELETE_LOADOUTS: String = "DELETE FROM match_loadout WHERE id = ANY($1::bigint[])"

/** `$1` public id, `$2` match id, ..., `$10` ghost player public id, `$11` disputed-by user public id. */
@Language("PostgreSQL")
const val INSERT_PARTICIPANT: String = """
    INSERT INTO match_participant (public_id, match_id, score, rank, outcome, ..., version, loadout_id, ghost_player_id, disputed_by_user_id)
    SELECT $1::uuid, $2::bigint, $3::numeric, $4::integer, $5::varchar, ..., 1, $9::bigint,
           (SELECT id FROM ghost_player WHERE public_id = $10::uuid),
           (SELECT id FROM app_user WHERE public_id = $11::uuid)
    WHERE EXISTS (SELECT 1 FROM ghost_player WHERE public_id = $10::uuid)
      AND ($11::uuid IS NULL OR EXISTS (SELECT 1 FROM app_user WHERE public_id = $11::uuid))
    RETURNING id"""
```

```kotlin
// replace every participant of the matches whose command names them
val loadoutIds = sql.query(MatchSql.DELETE_PARTICIPANTS, listOf(matchIds.toTypedArray())).mapNotNull { it.longOrNull("loadout_id") }
if (loadoutIds.isNotEmpty()) sql.execute(MatchSql.DELETE_LOADOUTS, listOf(loadoutIds.toTypedArray()))
writes.insertParticipants(sql, participants)
```

## SQL techniques

Every statement is static: the same text for every call, so it is prepared once and shows up as
one statement in `pg_stat_statements`.

- **Reads** keep today's shape: one-to-one relations as (LEFT) JOINs, one-to-many and link-table
  relations as `COALESCE((SELECT jsonb_agg(jsonb_build_object(...) ORDER BY <pk>) ...), '[]'::jsonb)`,
  every related table aliased by its relation's name (so a self-reference never shadows the row).
- **Search** renders only the request-dependent part over the generated fragments. A condition on
  a relation's field is an `EXISTS` along the relation chain, exactly as today. A condition that
  matches NULL on a one-to-one field also matches rows without the related row. Several values of
  an id or text type are one array (`= ANY($1::uuid[])`). A keyset seek is one row-value comparison
  when every sort column is NOT NULL and sorted the same way.
- **Batches** run a single-row statement through Vert.x `executeBatch`: one round trip, one result
  per parameter set (so `RETURNING` and "matched nothing" are known per input), and the SQL reads
  like hand-written SQL. A batch is reported to the listeners as one statement with its
  `batchSize`.
- **References by public id** are subselects, guarded by `EXISTS`: an input whose referenced row is
  missing writes nothing, and only then `requireReferences` runs the generated checks to report a
  `ReferencedEntityNotFoundException`.
- **Patches** are `SET c = CASE WHEN $flag THEN $value ELSE c END`: an unset `PatchField` keeps the
  column. A column is then written with its own value, which matters only to a trigger on
  `UPDATE OF <column>`.
- **A miss** - an update or delete that matched nothing - is explained afterwards by
  `diagnoseMiss` (the row is gone, or its version moved on) after the references are checked.
- **Children**: `REPLACE_ALL` removes first, then inserts. `UPSERT` reads the existing ids
  (soft-deleted ones included), splits with `childDiff` into update, insert and remove, and
  restores a soft-deleted child that is sent again.
- **Versions** are build-time choices: `version = version + 1`, an initial `1`. The runtime beans
  `VersionIncrementStrategy` and `InitialVersionStrategy` go away.
- **Placeholders** are Postgres' own `$1, $2, ...` with an explicit cast on every parameter, so the
  text in the build directory is byte for byte what Postgres and the test kit see.

## Accessors or a repository?

**Keep the four access classes and the mappers.** Each class maps to one annotation
(`@DomainCreate` to the create access) and stays small; services inject only what they need (the
`MatchValidator` only reads); the core interfaces `ReadAccess`, `CreateAccess`, ... stay the
contract. All SQL of an entity is in one file (`MatchSql`), which is where to read what an entity
does. A `MatchRepository` facade over the four classes is cheap to add later, but does not make the
code any more readable.

## jOOQ: removed

The plan was an optional `persistence-jooq` bridge first (phase 3) - the jOOQ methods of
`QueryExecutor` as extensions, so hand-written jOOQ code only changed its imports - and jOOQ's
removal later (phase 4). With 14 hand-written places in all, the bridge was skipped: they were
rewritten to SQL constants directly, so no module was built only to be removed again.

| Repository | Impact |
|---|---|
| stratastax-engine | 9 files of hand-written jOOQ (`MatchService`, `MatchParticipantDisputes`, `MatchEditApplier`, `GhostPlayerService`, `GroupService`, `CommunityService`, `PrivacySettingDefaults`, `UserProviderIdentityLookup`, `SelfGhostPlayerWriter`) become SQL constants; the jOOQ table classes (`server.schema.v1.tables.*`) and the `stratastax.jooq` convention plugin (jOOQ's `DDLDatabase` parses the migrations) go away |
| stratastax-game-catalog | `CandidateService`, `BggRankingStorage`, `PipelineControl`, and the same plugin |
| stratastax-http | `JooqIdempotencyStore` and `JooqCircuitStateStore` (and their tests) become `Sql*Store`s |
| stratastax-quarkus | `PersistenceProducer.dslContext`, `JooqSearchProducer` and the `Default*VersionStrategy` beans go away |
| stratastax-codegen | `JooqSchemaScanner`, `JooqSchemaValidator` and `JooqColumnConstantsGenerator` read the jOOQ-generated sources today. They are replaced by a schema reader that parses the migrations itself (jOOQ's DDL parser *inside the plugin*, at build time only, or a parser of our own). The `*Columns` constants keep their names, so the annotations on the entities do not change |
| stratastax-persistence | `persistence-jooq` is removed |

Hand-written jOOQ before, and after:

```kotlin
// MatchService.deleteMatch - TODAY
executor.executeUpdate(
    dsl.update(PARTICIPANTS)
        .set(PARTICIPANTS.DELETED_AT, OffsetDateTime.now(clock))
        .where(PARTICIPANTS.MATCH_ID.eq(DSL.field(dsl.select(MATCHES.ID).from(MATCHES).where(MATCHES.PUBLIC_ID.eq(matchId.value))))),
)
// LATER
private const val SOFT_DELETE_PARTICIPANTS = """
    UPDATE match_participant SET deleted_at = $2::timestamptz
    WHERE match_id = (SELECT id FROM match WHERE public_id = $1::uuid)"""

executor.execute(SOFT_DELETE_PARTICIPANTS, listOf(matchId.value, OffsetDateTime.now(clock)))
```

```kotlin
// JooqIdempotencyStore.claim - TODAY: ten DSL.field(DSL.name(...)) definitions, and
dsl.insertInto(TABLE).columns(SCOPE, REQUEST_ID, REQUEST_METHOD, REQUEST_PATH, REQUEST_CHECKSUM, CREATED_AT)
    .values(request.scope, request.key, request.method, request.path, request.checksum, OffsetDateTime.now(clock))
    .onConflictDoNothing()
    .returning(REQUEST_ID)

// LATER: the field definitions are gone
private const val CLAIM = """
    INSERT INTO idempotency_record (scope, request_id, request_method, request_path, request_checksum, created_at)
    VALUES ($1, $2, $3, $4, $5, $6)
    ON CONFLICT DO NOTHING
    RETURNING request_id"""

queryFirst(CLAIM, listOf(request.scope, request.key, request.method, request.path, request.checksum, OffsetDateTime.now(clock))) != null
```

```kotlin
// UserProviderIdentityLookup.find - TODAY: reuses UserEntityModel.fieldMapping.allAliasedFields and jOOQ joins
// LATER: reuses the generated fragments
private const val FIND_BY_PROVIDER = """
    SELECT ${UserSql.COLUMNS}, app_user.id AS entity_id
    FROM ${UserSql.FROM}
    JOIN app_user_identity identity ON app_user.id = identity.user_id
    WHERE identity.provider = $1 AND identity.provider_user_id = $2"""

executor.queryFirst(FIND_BY_PROVIDER, listOf(provider, providerUserId))
```

## Proof of concept

### What it shows

| Test | What it proves |
|---|---|
| `SqlFirstParityTest` | 14 scenarios run against the jOOQ-built and the SQL-first playset on a clean database each: the same results (entities, pages, counts, batch outcomes, exceptions with their messages) and the same rows afterwards |
| `SqlFirstPlaysetIntegrationTest` | every statement a write runs is literally a constant of `PlaysetSql`; `createAll` of 5 costs as many statements as one `create`; scopes, projections, `SKIP LOCKED` row locks and a self-reference |
| `PlaysetSqlPreparesTest` | Postgres prepares every statement of `PlaysetSql`, and the search statements for a filter on every field |
| `SearchSqlRenderTest` | the text and parameters a filter, sort, cursor and limit become |
| `VertxQueryExecutorSqlIntegrationTest` | numbered placeholders, JSON and array parameters, batches in one round trip, listener reports |

### Statements per scenario

Data statements (transaction control left out), from `build/reports/sql-first/parity.md`:

| Scenario | jOOQ route | SQL-first |
|---|---:|---:|
| create with every relation, findById | 10 | 10 |
| createAll with a missing reference | 11 | 9 |
| create with an unknown expansion | 3 | 3 |
| filters on every kind of field | 38 | 38 |
| sort on a relation, keyset paging | 17 | 17 |
| update fields, links and notes | 26 | 23 |
| update partially, unlink all | 14 | 14 |
| update failures | 20 | 21 |
| updateAll with misses | 7 | 7 |
| updateAll fallback | 7 | 9 |
| notes removed and restored | 26 | 24 |
| notes replaced | 17 | 16 |
| delete and deleteAll | 27 | 19 |
| unit of work | 9 | 9 |

SQL-first never needs more statements on a successful path. On a failing path it can need one or
two more, because it explains a miss after the fact instead of looking references up first.

### Code size

| | Lines (code lines) |
|---|---:|
| `persistence-reactive` main today (without license headers) | ~4,700 |
| Query building that goes away with the rollout | 3,519 (2,510) |
| SQL-first support added (`SearchSql`, `SearchSqlWriter`, `SqlWrites`) | 859 (594) |
| `persistence-reactive` main after the rollout | ~2,100 (~1,500) |
| Playset, configuration-object style (model + store) | 355 (325) |
| Playset, SQL-first (`PlaysetSql`, `PlaysetSearchSql`, mappers, access classes) | 1,079 (831) |

The library shrinks to less than half. The generated code grows instead, ~3x for the playset, but
every line is either literal SQL (240 lines) or a step you can read top to bottom. Nothing is
interpreted at runtime any more.

### Findings

- A statement without `RETURNING` has no column names in Vert.x (`columnsNames()` is `null`); the
  executor treats it as no rows.
- Vert.x checks an array parameter's Java type against the inferred SQL type, also when it is
  empty: an `= ANY($1::uuid[])` needs an `Array<UUID>`, so generated code passes
  `ids.toTypedArray()` rather than a `List`.
- Explicit casts on every parameter are needed where Postgres cannot infer a type (`$1 IS NULL`,
  the select list of `INSERT ... SELECT`, `CASE WHEN`); generated code casts every parameter, for
  one rule and for readability.
- A static projection cannot drop joins per request: it keeps the joins of the sortable
  one-to-one fields (a join on a foreign key to a primary key, cheap).
- Kotlin `const val` templates (`"SELECT $COLUMNS\nFROM $FROM"`) compose fragments at compile time,
  while `$1` stays literal (a digit cannot start a template).
- Long one-line statements break the 100-column style rule; generated code writes them as
  multi-line raw strings.

## Code generation (phase 2)

`codegen-persistence` now renders, per entity, `${Entity}Sql`, the mappers and the four access
classes in this shape, and the entity model's `searchSql`; [Code generation](code-generation.md)
is the contract, with the engine's `Playset` as the example. Deviations from the PoC:

- The search fragments live in the entity model (`PlaysetEntityModel.searchSql`), next to the
  search configuration, rather than in an object of their own.
- Table and column names come from the scanned jOOQ schema (`DSL.name`, lowercased as Postgres
  folds them), the casts from its data types; messages keep the declared names (`PLAYSET`).
- The version column follows the declared version field and its column, as the old access classes
  did; a new row starts at `1`.
- Generated code is written the way the stratastax style formats it - a lambda that does not fit
  gets its body on its own line, a parent's key is named before it is paired, a signature with two
  or more parameters has one per line - so a consumer's formatter changes nothing.

Verified against the consumers (nothing committed, everything published to Maven local):

| | Result |
|---|---|
| stratastax-codegen `./gradlew build` | green: style, package rules, `codegen-persistence` 203 tests (the generator and entity model tests rewritten for the new output) |
| stratastax-engine | 169/169, including the query-count tests |
| stratastax-game-catalog | 70/70 |

What the consumers needed:

- **engine**: `UserProviderIdentityLookup` rewritten as two constants over `UserSql.COLUMNS` and
  `UserSql.FROM` (the example in the jOOQ section above); one expectation changed - deleting a
  playset is now two statements (the owner check and the soft delete) instead of three, since a
  soft delete leaves the relations alone.
- **game-catalog**: two imports removed - `findAllForUpdate` is now a member of the read access.
- **library**: the executor converts what jOOQ converted before - a `URI`/`URL` to text, an enum to
  its name, an `Instant` to a timestamp - and `toSqlArray()` turns a collection into the typed array
  an array column needs.

Found on the way and fixed in the generator: deleting the owned targets of a link table read the
target key from the wrong column of the deleted links; a link's ids are read without a needless
safe call when the command property is non-nullable.

Size: the engine's `Match` is ~2,000 generated lines (`MatchSql` 415, `MatchEntityModel` 754, the
access classes 620, the mappers 208), against ~1,700 lines of configuration objects before - about
the same, but every line is a statement or a step that runs as written.

## Decisions

Taken on 2026-10-19:

1. **Children are synchronized by generated code**, not by a library helper: the steps stay
   readable top to bottom in the access class (~100 lines for the playset's notes and reactions).
2. **Parameters stay positional** (`$1, $2, ...`): the text in the build directory is exactly what
   runs, documented by the statement's kdoc and a comment per parameter in the mapper.
3. **`unnest` only for specific bulk paths**: `executeBatch` of a single-row statement is the
   default; a bulk import that needs set-based inserts gets a dedicated statement.

## Rollout

1. **PoC** (done): this document and the hand-written playset.
2. **Codegen** (done, see above): renderers for `*Sql`, the mappers and the access classes in
   `codegen-persistence`; `EntityModelRenderer` lost the `JooqField` and groups part and gained
   `searchSql`; the engine and the game catalog run on it.
3. **Clean-up and chain** (done): `Jooq*Access`, `JooqEntityBinding`, `JooqField*`,
   `TranslationPlan`, `JooqSearchQueryTranslator` and `internal/*Writes` removed; the Quarkus
   producers adapted. Published in order: persistence, codegen, quarkus, http, security, engine
   (and game-catalog).
4. **jOOQ removed** entirely (done, see the impact table) - without the bridge.

## Phases 3 and 4

What changed, per repository:

- **persistence**: the old route and its tests are gone. `persistence-reactive`'s main code is
  1,853 lines (without license headers), down from ~4,700. The parity test against the old route
  became `PlaysetScenarioTest`: the same 14 scenarios, checked against recorded snapshots
  (`src/test/resources/scenarios`). Unused SPI went too: `PersistenceDialect.isReserved` (code
  generation aliases reserved words itself) and core's `CreateMapper`/`UpdateMapper`.
- **codegen**: `MigrationSchemaReader` reads the schema from the Flyway migrations
  (`V<n>__*.sql`: `CREATE`/`ALTER`/`DROP TABLE`, with `[no-codegen start]`/`[no-codegen stop]`
  sections skipped) instead of jOOQ-generated sources. Before the jOOQ scanner was removed, both
  read the engine's 28 tables and the game catalog's 6 identically. The Gradle block
  `jooq { schemaPackage; generatedSourceRoots }` became `schema { migrations; columnsPackage }`;
  the `*Columns` constants kept their names, so no annotation changed.
- **quarkus**: the `DSLContext` producer, `JooqSearchProducer` and the version strategies are
  gone. The opt-in build property is now `stratastax.persistence.enabled`
  (was `stratastax.search.jooq.enabled`).
- **http**: `SqlIdempotencyStore` and `SqlCircuitStateStore` replace their jOOQ versions.
- **engine and game catalog**: 9 and 3 files of hand-written jOOQ became SQL constants (the
  game catalog's `countBy(column)` became two fixed statements rather than a column name in SQL
  text). The `stratastax.jooq` convention plugin became `stratastax.schema`, which only copies the
  migrations of the schema contract for Flyway and code generation.

Every build is green: persistence 242 tests, codegen 786, quarkus 44, http 54, engine 169, game
catalog 70.
