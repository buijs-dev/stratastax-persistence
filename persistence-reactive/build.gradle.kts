plugins {
    id("stratastax.library")
}

description =
    "jOOQ/Vert.x reactive Postgres implementation of the generic persistence API used throughout Stratastax"

@OptIn(org.jetbrains.kotlin.gradle.dsl.abi.ExperimentalAbiValidation::class)
kotlin {
    explicitApi()
    abiValidation()
}

dependencies {
    api(project(":persistence-core"))
    api(stratastax.entity)
    api(stratastax.search.core)
    api(libs.jooq)
    api(stratastax.mutiny.vertx.pg.client)

    implementation(stratastax.ambient.context)
    implementation(libs.smallrye.mutiny.kotlin)
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.kotlin.logging)

    testImplementation(project(":persistence-test"))
    testImplementation(libs.bundles.test)
    testImplementation(libs.testcontainers.postgresql)
    testImplementation(libs.testcontainers.junit)
    testImplementation(stratastax.postgresql)
}
