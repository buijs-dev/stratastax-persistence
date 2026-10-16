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

/*
 * Jackson 3 (tools.jackson) is this repository's only JSON library. A Jackson 2 runtime
 * (com.fasterxml.jackson.core:jackson-core/-databind) must never come in through a dependency
 * of our own - jackson-annotations is shared by Jackson 2 and 3 by design and allowed.
 * persistence-reactive is the one exception for jackson-core: Vert.x 4 itself depends on it.
 */
val jackson2Runtime = setOf("jackson-core", "jackson-databind")
val vertxProjects = setOf(":persistence-reactive")

val checkNoJackson2 by tasks.registering {
    group = "verification"
    description = "Fails when a Jackson 2 runtime ends up on a module's runtime classpath."
    val classpaths =
        subprojects.associate { project ->
            project.path to
                project.configurations.named("runtimeClasspath").map { configuration ->
                    configuration.incoming.resolutionResult.allComponents
                        .mapNotNull { it.moduleVersion }
                        .filter { it.group == "com.fasterxml.jackson.core" }
                        .map { it.name }
                }
        }
    doLast {
        val offenders =
            classpaths.flatMap { (path, modules) ->
                modules.get()
                    .filter { it in jackson2Runtime }
                    .filterNot { it == "jackson-core" && path in vertxProjects }
                    .map { "$path -> com.fasterxml.jackson.core:$it" }
            }
        check(offenders.isEmpty()) { "Jackson 2 runtime on the classpath:\n" + offenders.joinToString("\n") }
    }
}

/* `!!` is not allowed in production code: model the state so the compiler can prove it instead. */
val checkNoNonNullAssertions by tasks.registering {
    group = "verification"
    description = "Fails when production Kotlin code contains a `!!` non-null assertion."
    val sources = subprojects.map { it.fileTree("src/main/kotlin") { include("**/*.kt") } }
    inputs.files(sources)
    doLast {
        val offenders =
            sources.flatMap { tree -> tree.files }.flatMap { file ->
                file.readLines().mapIndexedNotNull { index, line ->
                    val code = line.substringBefore("//").trimStart()
                    val isDoc = code.startsWith("*") || code.startsWith("/*")
                    if (!isDoc && "!!" in code) "${file.path}:${index + 1}: ${line.trim()}" else null
                }
            }
        check(offenders.isEmpty()) { "`!!` found in production code:\n" + offenders.joinToString("\n") }
    }
}

tasks.named("check") { dependsOn(checkNoJackson2, checkNoNonNullAssertions) }
