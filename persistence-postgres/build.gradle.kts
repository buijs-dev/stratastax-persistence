plugins {
    id("stratastax.library")
}

description =
    "PostgreSQL dialect of the Stratastax persistence runtime: its transaction statements, " +
    "search syntax and parameter binding, on the Vert.x reactive Postgres client"

@OptIn(org.jetbrains.kotlin.gradle.dsl.abi.ExperimentalAbiValidation::class)
kotlin {
    explicitApi()
    abiValidation()
}

dependencies {
    api(project(":persistence-reactive"))
    api(stratastax.mutiny.vertx.pg.client)

    testImplementation(project(":persistence-test"))
    testImplementation(libs.bundles.test)
    testImplementation(libs.testcontainers.postgresql)
    testImplementation(libs.testcontainers.junit)
    testImplementation(stratastax.postgresql)
    testImplementation(stratastax.ambient.context)
    testImplementation(libs.smallrye.mutiny.kotlin)
    testImplementation(libs.kotlinx.coroutines.core)
}
