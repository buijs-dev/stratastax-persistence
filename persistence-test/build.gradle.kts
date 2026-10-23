plugins {
    id("stratastax.library")
}

description =
    "Test harness for asserting on the queries generated persistence code actually runs, " +
    "independent of the database; persistence-postgres-test adds PostgreSQL"

@OptIn(org.jetbrains.kotlin.gradle.dsl.abi.ExperimentalAbiValidation::class)
kotlin {
    explicitApi()
    abiValidation()
}

dependencies {
    // Exposed to consumers: TestExecution carries core's QueryExecution.
    api(project(":persistence-core"))

    implementation(libs.kotlinx.coroutines.core)

    testImplementation(libs.bundles.test)
    testImplementation(project(":persistence-postgres"))
    testImplementation(project(":persistence-postgres-test"))
}
