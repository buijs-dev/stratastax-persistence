plugins {
    id("stratastax.library")
}

description =
    "PostgreSQL side of the persistence test kit: a Testcontainers database and the " +
    "EXPLAIN-based query plan analyzer behind the plan assertions of persistence-test"

@OptIn(org.jetbrains.kotlin.gradle.dsl.abi.ExperimentalAbiValidation::class)
kotlin {
    explicitApi()
    abiValidation()
}

dependencies {
    // Exposed to consumers: PostgresQueryPlanAnalyzer implements persistence-test's
    // QueryPlanAnalyzer, and PostgresTestDatabase exposes Testcontainers' PostgreSQLContainer.
    api(project(":persistence-test"))
    api(libs.testcontainers.postgresql)

    implementation(libs.testcontainers.junit)
    implementation(libs.junit.api)
    implementation(libs.kotlinx.coroutines.core)
    implementation(stratastax.postgresql)
    // EXPLAIN (FORMAT JSON) parsing; Jackson 3 only - see the README's dependency notes.
    implementation(stratastax.jackson3.databind)

    testImplementation(libs.bundles.test)
}
