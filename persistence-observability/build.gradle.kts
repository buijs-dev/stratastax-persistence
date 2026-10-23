plugins {
    id("stratastax.library")
}

description =
    "Production observability for persistence: every statement as an OpenTelemetry span under " +
    "the request that caused it, grouped per transaction, and a per-request query profile"

@OptIn(org.jetbrains.kotlin.gradle.dsl.abi.ExperimentalAbiValidation::class)
kotlin {
    explicitApi()
    abiValidation()
}

dependencies {
    // Exposed to consumers: the listener implements core's QueryExecutionListener, the context
    // elements are ambient-context PropagatedContextElements and carry an OpenTelemetry Context.
    api(project(":persistence-core"))
    api(stratastax.ambient.context)
    api(stratastax.opentelemetry.api)

    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.kotlin.logging)

    testImplementation(libs.bundles.test)
    testImplementation(stratastax.opentelemetry.sdk.testing)
}
