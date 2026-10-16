plugins {
    id("stratastax.library")
}

description =
    "Test harness for asserting on the queries generated persistence code actually runs against PostgreSQL"

dependencies {
    // Aligns the unversioned org.postgresql:postgresql coordinate below with
    // whatever Quarkus version this repo targets, same as persistence-vertx.
    implementation(platform(libs.quarkus.bom))

    implementation(project(":persistence-core"))
    implementation(libs.kotlin.logging)
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.bundles.test)

    // Exposed to downstream testImplementation consumers as this module's own
    // reusable Postgres-fixture support (PostgresTestDatabase) - not a
    // test-only dependency of this module itself.
    implementation(libs.testcontainers.postgresql)
    implementation(libs.testcontainers.junit)
    implementation(libs.postgresql)

    testImplementation(libs.logback)
    testImplementation(project(":persistence-reactive"))
    testImplementation(libs.jooq)
    testImplementation(libs.vertx.pg.client)
    testImplementation(libs.vertx.sql.client)
    testImplementation(libs.smallrye.mutiny.vertx.pg.client)
    testImplementation(libs.smallrye.mutiny.kotlin)
}
