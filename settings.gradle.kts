pluginManagement {
    repositories {
        mavenLocal()
        maven("https://maven.pkg.github.com/buijs-dev/*") {
            credentials {
                username =
                    providers
                        .gradleProperty("gpr.user")
                        .orElse(providers.environmentVariable("GITHUB_ACTOR"))
                        .orNull
                password =
                    providers
                        .gradleProperty("gpr.key")
                        .orElse(providers.environmentVariable("GITHUB_TOKEN"))
                        .orNull
            }

            mavenContent {
                releasesOnly()
                includeGroupByRegex("dev\\.buijs.*")
            }
        }

        maven("https://repo.repsy.io/mvn/buijs-dev/maven") {
            mavenContent {
                snapshotsOnly()
                includeGroupByRegex("dev\\.buijs.*")
            }
        }

        mavenCentral()
        gradlePluginPortal()
    }
}

// The stratastax conventions (stratastax.* plugins), repositories and the shared `libs` catalog.
plugins {
    id("dev.buijs.stratastax.settings") version "1.0.0"
}

include(":persistence-core")
include(":persistence-csv")
include(":persistence-postgres")
include(":persistence-postgres-test")
include(":persistence-observability")
include(":persistence-reactive")
include(":persistence-test")
