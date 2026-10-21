plugins {
    id("stratastax.library")
}

description =
    "Test harness for asserting on the queries generated persistence code actually runs against PostgreSQL"

@OptIn(org.jetbrains.kotlin.gradle.dsl.abi.ExperimentalAbiValidation::class)
kotlin {
    explicitApi()
    abiValidation()
}

dependencies {
    // Exposed to consumers: TestExecution carries core's QueryExecution, and PostgresTestDatabase
    // exposes Testcontainers' PostgreSQLContainer.
    api(project(":persistence-core"))
    api(libs.testcontainers.postgresql)

    implementation(libs.testcontainers.junit)
    implementation(libs.junit.api)
    implementation(libs.kotlinx.coroutines.core)
    implementation(stratastax.postgresql)
    // EXPLAIN (FORMAT JSON) parsing; Jackson 3 only - see the README's dependency notes.
    implementation(stratastax.jackson3.databind)

    testImplementation(libs.bundles.test)
    testImplementation(project(":persistence-postgres"))
}
