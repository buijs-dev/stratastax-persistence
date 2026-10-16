plugins {
    id("stratastax.library")
}

description = "Core module for the generic persistence API used throughout Stratastax"

dependencies {
    implementation(stratastax.entity)
    implementation(stratastax.search.core)
    implementation(libs.kotlin.logging)
    testImplementation(libs.mockk)
    testImplementation(libs.bundles.test)
}
