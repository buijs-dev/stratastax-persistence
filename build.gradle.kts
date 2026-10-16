plugins {
    id("stratastax.coverage")
    id("stratastax.dokka")
}

// One coverage report and one documentation site over every module.
dependencies {
    subprojects.forEach {
        kover(project(it.path))
        dokka(project(it.path))
    }
}
