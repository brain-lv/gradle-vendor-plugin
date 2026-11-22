/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 *
 * Copyright (c) 2026 Edgars Grinvalds <edgars.grinvalds@brain.lv>
 */

plugins {
    application
    id("lv.brain.vendor-consumer-project")
}

java {
    toolchain { languageVersion = JavaLanguageVersion.of(17) }
}

dependencies {
    implementation(vendor.bundle("platform"))
}

application {
    mainClass.set("com.example.app.Main")
}

// Prints what Gradle compiles against and what it runs with.
tasks.register("printVendorClasspaths") {
    val compile = configurations.compileClasspath.get().incoming.files
    val runtime = configurations.runtimeClasspath.get().incoming.files
    doLast {
        println("COMPILE=" + compile.files.map { it.name }.sorted())
        println("RUNTIME=" + runtime.files.map { it.name }.sorted())
    }
}

// Fails when the compile classpath is not the small one: the vendor API and what it exposes, never commons-lang3.
tasks.register("checkVendorClasspaths") {
    val compile = configurations.compileClasspath.get().incoming.files
    val runtime = configurations.runtimeClasspath.get().incoming.files
    doLast {
        val compileNames = compile.files.map { it.name }
        val runtimeNames = runtime.files.map { it.name }
        check("acme-core.jar" in compileNames && compileNames.any { it.startsWith("jackson-databind") }) { "compile classpath: $compileNames" }
        check(compileNames.none { it.startsWith("commons-lang3") }) { "commons-lang3 must be runtime only: $compileNames" }
        check(runtimeNames.any { it.startsWith("commons-lang3") } && runtimeNames.any { it.startsWith("spring-core") }) { "runtime classpath: $runtimeNames" }
    }
}
