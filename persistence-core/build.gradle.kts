plugins {
    id("stratastax.library")
}

description = "Core module for the generic persistence API used throughout Stratastax"

@OptIn(org.jetbrains.kotlin.gradle.dsl.abi.ExperimentalAbiValidation::class)
kotlin {
    explicitApi()
    abiValidation()
}

dependencies {
    // ReadAccess exposes search-core's SearchQuery/Page in its public API.
    api(stratastax.search.core)

    testImplementation(libs.bundles.test)
    testImplementation(libs.kotlinx.coroutines.core)
}
