plugins {
    id("stratastax.library")
}

description =
    "Vert.x reactive implementation of the generic persistence API used throughout Stratastax: " +
    "literal SQL through a pluggable dialect, search rendering and the checks of a write"

@OptIn(org.jetbrains.kotlin.gradle.dsl.abi.ExperimentalAbiValidation::class)
kotlin {
    explicitApi()
    abiValidation()
}

dependencies {
    api(project(":persistence-core"))
    api(stratastax.entity)
    api(stratastax.search.core)
    api(stratastax.mutiny.vertx.sql.client)

    implementation(stratastax.ambient.context)
    implementation(libs.smallrye.mutiny.kotlin)
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.kotlin.logging)

    testImplementation(libs.bundles.test)
}
