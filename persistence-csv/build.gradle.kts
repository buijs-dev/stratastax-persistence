plugins {
    id("stratastax.library")
}

description =
    "commons-csv implementation of the generic row-mapping API used throughout Stratastax"

@OptIn(org.jetbrains.kotlin.gradle.dsl.abi.ExperimentalAbiValidation::class)
kotlin {
    explicitApi()
    abiValidation()
}

dependencies {
    api(project(":persistence-core"))
    implementation(libs.commons.csv)
    implementation(stratastax.jackson3.databind)
    testImplementation(libs.bundles.test)
}
