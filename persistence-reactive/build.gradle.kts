plugins {
    id("stratastax.library")
}

description =
    "jOOQ/Vert.x reactive Postgres implementation of the generic persistence API used throughout Stratastax"

dependencies {
    // Aligns the un-versioned io.vertx/io.smallrye.reactive coordinates below
    // (and the jOOQ/Postgres-facing test dependencies) with whatever Quarkus
    // version this repo targets - deliberately not this module applying the
    // io.quarkus Gradle plugin itself (that's for quarkus-starter, an actual
    // CDI/Quarkus extension; this module stays plain, framework-agnostic
    // classes only).
    implementation(platform(libs.quarkus.bom))

    implementation(stratastax.ambient.context)
    implementation(stratastax.entity)
    implementation(stratastax.search.core)
    implementation(project(":persistence-core"))
    implementation(libs.jooq)
    implementation(libs.vertx.pg.client)
    implementation(libs.vertx.sql.client)
    implementation(libs.smallrye.mutiny.vertx.pg.client)
    implementation(libs.smallrye.mutiny.kotlin)
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.kotlin.logging)
    implementation(libs.mockk)
    implementation(libs.bundles.test)

    testImplementation(libs.logback)
    // Testcontainers-backed integration test against a real Postgres - needs
    // Docker to run (VertxQueryExecutorIntegrationTest is skipped otherwise);
    // the rest of this module's tests need no database.
    testImplementation(libs.testcontainers.postgresql)
    testImplementation(libs.testcontainers.junit)
    testImplementation(libs.postgresql)
}
