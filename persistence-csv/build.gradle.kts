plugins {
    id("stratastax.library")
}

description =
    "commons-csv implementation of the generic CSV-row-mapping API used throughout Stratastax"

dependencies {
    implementation(stratastax.entity)
    implementation(project(":persistence-core"))
    implementation(libs.commons.csv)
    implementation(libs.kotlin.logging)
    implementation(libs.mockk)
    implementation(libs.bundles.test)

    testImplementation(libs.logback)
}
