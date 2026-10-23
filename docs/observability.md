# Observability: from HTTP request to every query

`persistence-test` tells you in a test which statements a call runs. In production you want the
same answer per request: which queries did `GET /matches/{id}` run, in which transaction, when,
and for how long. `persistence-observability` gives that answer as OpenTelemetry spans.

```kotlin
implementation("dev.buijs.stratastax:persistence-observability:<version>")
```

The module needs only the OpenTelemetry **API**; the application brings the SDK and the exporter
(in Quarkus: `quarkus-opentelemetry`).

## What you get

```
HTTP GET /matches/{id}                        ├──────────────────────────┤   server span (Quarkus)
  transaction                                   ├───────────────────┤        BEGIN .. COMMIT
    SELECT                                       ├──┤
    SELECT                                           ├┤├┤├┤ …                 a probable N+1
  SELECT                                                              ├┤     outside a transaction
```

- One `CLIENT` span per statement, at the exact time it was sent (`QueryExecution.startedAt`) and
  for exactly as long as it took, with `db.system.name`, `db.operation.name`, `db.query.text`,
  `db.response.returned_rows`, `stratastax.tx.id`, `stratastax.batch.size` and
  `stratastax.request.id`.
- One `transaction` span per transaction, from its `BEGIN` to its `COMMIT` or `ROLLBACK`
  (`stratastax.tx.outcome`); its statements are its children and savepoints are events on it.
- A failed statement has status `ERROR`, `error.type` and the exception as an event.
- On the request's span, the totals of the request: `stratastax.db.query_count`,
  `stratastax.db.transaction_count`, `stratastax.db.failed_count`, `stratastax.db.duration_ms`
  and `stratastax.db.n_plus_one`, plus a warning in the log for a probable N+1 problem or a
  request over its query budget.

Bind values are **never** exported by default: the SQL carries `$1, $2, ...` placeholders only.
Set `QueryTracingOptions.parameterRenderer` to export them; the renderer decides what to redact.

## How the request reaches the query

The correlation travels in the coroutine context, like the rest of the ambient context:

1. The request's ambient context gets a `QueryCorrelation` (request id and the OpenTelemetry
   context holding the server span) and a fresh `RequestQueryProfile`. Generated resources already
   run in `inheritedUni(ambientContext.current(), ...)`, so an `AmbientContextContributor` that
   returns both is all it takes.
2. A transaction keeps the caller's coroutine context, and `QueryExecutionListener.executed` is a
   suspend function called in the coroutine that ran the statement - so
   `OpenTelemetryQueryListener` finds the correlation with `currentCoroutineContext()`. No
   thread-locals are involved.
3. When the response is written, `RequestQueryReporter.report(profile, span, requestId)` puts the
   totals on the request's span.

## Wiring

```kotlin
val listener =
    OpenTelemetryQueryListener(
        tracer = openTelemetry.getTracer("stratastax-persistence"),
        options = QueryTracingOptions(dbSystem = "postgresql"),
    )

val transactional = VertxTransactionalExecutor(pool, PostgresDialect, listeners = listOf(listener))

// per request, in the coroutine context the request runs in:
val context = QueryCorrelation(requestId, Context.current(), method, routeTemplate) + RequestQueryProfile()
```

In a Quarkus application the starter registers the listener as a `QueryExecutionListener` bean (the
persistence producer collects all of them), contributes the correlation from the incoming
`traceparent` / `X-Request-Id`, and reports the profile in a response filter. Turn off Quarkus' own
reactive SQL client instrumentation (`quarkus.otel.instrument.vertx-sql-client=false`) to avoid a
second, less detailed span per statement.

## Options

| `QueryTracingOptions` | Default  | Meaning                                                                                 |
|-----------------------|----------|-----------------------------------------------------------------------------------------|
| `dbSystem`            | required | `db.system.name` of every span, e.g. `postgresql`                                       |
| `captureQueryText`    | `true`   | set `db.query.text` (placeholders only, never values)                                   |
| `parameterRenderer`   | `null`   | export bind values as `db.query.parameter.<index>`, rendered (and redacted) by this     |
| `traceUncorrelated`   | `false`  | trace statements without a request (background jobs) under the current OTel context     |
| `maxOpenTransactions` | `10_000` | transactions with an open span at once; beyond it statements hang under the request     |

`RequestQueryProfile(maxDistinctQueries = 200)` bounds the SQL texts it keeps per request;
`RequestQueryReporter(nPlusOneThreshold = 3, queryBudget = 50)` sets when it warns. The N+1 rule is
the same as `assertNoNPlusOne` in the test kit: one SQL text that ran `threshold` times or more.
