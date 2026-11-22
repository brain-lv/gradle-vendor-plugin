/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 *
 * Copyright (c) 2026 Edgars Grinvalds <edgars.grinvalds@brain.lv>
 */

package lv.brain.gradle.dsl

import lv.brain.gradle.support.Dep
import lv.brain.gradle.support.DistributorSettings
import lv.brain.gradle.support.Dsl
import lv.brain.gradle.support.PluginTestBase
import lv.brain.gradle.support.Scripts
import lv.brain.gradle.support.classpath
import lv.brain.gradle.support.moduleDependencies
import org.gradle.testkit.runner.TaskOutcome
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import java.io.File

/**
 * Every scenario runs twice, once with Groovy build scripts and once with Kotlin build scripts (see the two subclasses
 * below). The scripts are generated from the same description, so a scenario that passes in one language and fails in
 * the other points at a DSL problem, not at a test problem.
 */
abstract class DslParityTest(dsl: Dsl) : PluginTestBase(dsl) {

    private val categories = mapOf("client" to listOf("client/lib/**"), "server" to listOf("server/lib"))
    private val bundles = "ivy/lv.brain.vendor.bundle"
    private val compileSplit = DistributorSettings(slimJars = true, compilePackages = listOf("com.demo"), categories = categories)

    // ---- distributor: the settings DSL ----

    @Test
    fun `category includes select the jars that are packaged`() {
        createSdk(DistributorSettings(categories = categories))

        val result = gradle(sdkDir, "packageVendorDistribution")

        assertEquals(TaskOutcome.SUCCESS, result.task(":packageVendorDistribution")?.outcome, why(result.output))
        val names = distribution().keys
        listOf("client/lib/demo-client-1.0.0.jar", "client/lib/leak-1.0.0.jar", "client/lib/commons-lite-3.8.jar", "server/lib/demo-server-1.0.0.jar")
            .forEach { assertTrue(it in names, why("$it missing from $names")) }
        assertTrue("tools/unmatched-1.0.jar" in names && names.none { it.startsWith("ivy/") && "unmatched" in it }, why("a jar that no category includes became a module: $names"))
        assertTrue("$bundles/client-2026.1.0/client-2026.1.0.module" in names, why(names))
        assertTrue("$bundles/server-2026.1.0/server-2026.1.0.module" in names, why(names))
    }

    @Test
    fun `slimJars, compilePackages and defaultCategory from the settings block`() {
        createSdk(compileSplit.copy(defaultCategory = "misc"))

        gradle(sdkDir, "packageVendorDistribution")

        val files = distribution()
        assertTrue("ivy/lv.brain.vendor.client/demo-client-1.0.0/demo-client-1.0.0-slim.jar" in files, why(files.keys))
        assertTrue("ivy/org.lite/commons-lite-3.8/commons-lite-3.8-slim.jar" !in files, why("a runtime only jar got a slim jar"))
        assertTrue(String(files.getValue("ivy/vendor-layout.properties")).contains("compileSplit=true"), why("layout marker"))
        assertTrue("tools/unmatched-1.0.jar" in files && "$bundles/misc-2026.1.0/misc-2026.1.0.module" in files, why("defaultCategory: ${files.keys}"))

        val client = "$bundles/client-2026.1.0/client-2026.1.0.module"
        assertEquals(listOf("demo-client", "leak"), moduleDependencies(files, client, "apiElements"), why("compile classpath of the bundle"))
        assertEquals(listOf("commons-lite", "demo-client", "leak"), moduleDependencies(files, client, "runtimeElements"), why("runtime classpath of the bundle"))
    }

    @Test
    fun `dependencyExcludeClasses in the settings block keeps a jar out of the compile classpath`() {
        createSdk(compileSplit.copy(dependencyExcludeClasses = listOf("org.leak")))

        gradle(sdkDir, "packageVendorDistribution")

        val files = distribution()
        val client = "$bundles/client-2026.1.0/client-2026.1.0.module"
        // Leak is only on the compile classpath because Api returns it; with its package excluded nothing promotes it
        assertEquals(listOf("demo-client"), moduleDependencies(files, client, "apiElements"), why("compile classpath of the bundle"))
        assertEquals(listOf("commons-lite", "demo-client", "leak"), moduleDependencies(files, client, "runtimeElements"), why("the jar is still packaged"))
        assertTrue("client/lib/leak-1.0.0.jar" in files && "ivy/lv.brain.vendor.client/leak-1.0.0/leak-1.0.0.module" in files, why(files.keys))
    }

    @Test
    fun `dependencyExcludeJars in the settings block removes the edges of a jar`() {
        createSdk(compileSplit.copy(dependencyExcludeJars = listOf("client/lib/commons-lite-")))

        gradle(sdkDir, "packageVendorDistribution")

        val files = distribution()
        val demoClient = "ivy/lv.brain.vendor.client/demo-client-1.0.0/demo-client-1.0.0.module"
        // demo-client creates a commons-lite instance in a method body: a dependency, unless that jar is left out
        assertEquals(listOf("leak"), moduleDependencies(files, demoClient, "runtimeElements"), why(files.keys))
        assertTrue(
            "client/lib/commons-lite-3.8.jar" in files && "ivy/org.lite/commons-lite-3.8/commons-lite-3.8.module" in files,
            why("the excluded jar is still packaged: ${files.keys}")
        )
    }

    @Test
    fun `a classifier function assigns the categories`() {
        createSdk(DistributorSettings(classifierByTopFolder = true))

        gradle(sdkDir, "packageVendorDistribution")

        val files = distribution()
        assertEquals(listOf("demo-server"), moduleDependencies(files, "$bundles/server-2026.1.0/server-2026.1.0.module", "runtimeElements"), why(files.keys))
        assertEquals(
            listOf("commons-lite", "demo-client", "leak"),
            moduleDependencies(files, "$bundles/client-2026.1.0/client-2026.1.0.module", "runtimeElements"),
            why(files.keys)
        )
    }

    @Test
    fun `vendor properties alone configure the distributor`() {
        createSdk(
            DistributorSettings(),
            "vendor.slimJars=true", "vendor.compilePackages=com.demo",
            "vendor.category.client=client/lib/**", "vendor.category.server=server/lib"
        )

        gradle(sdkDir, "packageVendorDistribution")

        val files = distribution()
        assertTrue(String(files.getValue("ivy/vendor-layout.properties")).contains("compileSplit=true"), why(files.keys))
        assertEquals(
            listOf("demo-client", "leak"),
            moduleDependencies(files, "$bundles/client-2026.1.0/client-2026.1.0.module", "apiElements"),
            why("compile classpath of the bundle")
        )
        assertTrue("$bundles/server-2026.1.0/server-2026.1.0.module" in files, why(files.keys))
    }

    @Test
    fun `vendor properties alone exclude classes and jars from the dependency check`() {
        createSdk(
            DistributorSettings(),
            "vendor.slimJars=true", "vendor.compilePackages=com.demo",
            "vendor.dependencyExcludeClasses=org.leak, org.nothing.Here",
            "vendor.dependencyExcludeJars=client/lib/commons-lite-",
            "vendor.category.client=client/lib/**", "vendor.category.server=server/lib"
        )

        gradle(sdkDir, "packageVendorDistribution")

        val files = distribution()
        assertEquals(
            listOf("demo-client"),
            moduleDependencies(files, "$bundles/client-2026.1.0/client-2026.1.0.module", "apiElements"),
            why("compile classpath of the bundle")
        )
        assertEquals(
            emptyList<String>(),
            moduleDependencies(files, "ivy/lv.brain.vendor.client/demo-client-1.0.0/demo-client-1.0.0.module", "runtimeElements"),
            why("Leak and commons-lite are both out of the picture")
        )
    }

    @Test
    fun `the settings block wins over vendor properties for the exclusions`() {
        createSdk(
            compileSplit.copy(dependencyExcludeClasses = listOf("org.leak")),
            "vendor.dependencyExcludeClasses=org.lite"
        )

        gradle(sdkDir, "packageVendorDistribution")

        val files = distribution()
        val demoClient = "ivy/lv.brain.vendor.client/demo-client-1.0.0/demo-client-1.0.0.module"
        // org.leak from the block replaces org.lite from the properties: commons-lite stays a dependency, leak does not
        assertEquals(listOf("commons-lite"), moduleDependencies(files, demoClient, "runtimeElements"), why(files.keys))
    }

    @Test
    fun `a consumer that sets an exclusion is rejected, it is a distributor setting`() {
        createSdk(compileSplit)
        gradle(sdkDir, "generateVendorIvyRepo")
        createApp(listOf(Dep.Bundle("server")))
        File(appDir, "vendor.properties").appendText("vendor.dependencyExcludeClasses=org.leak\n")

        val result = gradleFails(appDir, "help")

        assertTrue(result.output.contains("vendor.dependencyExcludeClasses"), why(result.output))
    }

    // ---- consumer: build scripts of the application ----

    @Test
    fun `an application compiles against the compile jars and runs on the full jars`() {
        createSdk(compileSplit)
        gradle(sdkDir, "generateVendorIvyRepo")
        // Extra needs Api, and Leak because the public API of Api returns it
        createApp(
            listOf(Dep.Bundle("server")),
            extraSource = "package app;\npublic class Extra { com.demo.client.Api api; Object leak() { return api.leak(); } }\n"
        )

        val output = gradle(appDir, "compileJava", "printClasspaths", "runApp").output

        assertEquals(
            listOf("demo-client-1.0.0-slim.jar", "demo-server-1.0.0-slim.jar", "leak-1.0.0-slim.jar"),
            classpath(output, "COMPILE"), why("compile classpath")
        )
        assertEquals(
            listOf("commons-lite-3.8.jar", "demo-client-1.0.0.jar", "demo-server-1.0.0.jar", "leak-1.0.0.jar"),
            classpath(output, "RUNTIME"), why("runtime classpath")
        )
        listOf("com.demo.client.Api", "org.leak.Leak", "org.lite.Util").forEach {
            assertTrue("LOADED $it" in output, why("$it was not loaded at run time:\n$output"))
        }
    }

    @Test
    fun `vendor module resolves a single module by name, with or without its group`() {
        createSdk(DistributorSettings(slimJars = true, categories = categories))
        gradle(sdkDir, "generateVendorIvyRepo")
        createApp(
            listOf(
                Dep.Module("demo-client"),
                Dep.Module("leak", group = "lv.brain.vendor.client"),
                Dep.Module("commons-lite", group = "org.lite")
            )
        )

        val compile = classpath(gradle(appDir, "printClasspaths").output, "COMPILE")

        assertEquals(listOf("commons-lite-3.8-slim.jar", "demo-client-1.0.0-slim.jar", "leak-1.0.0-slim.jar"), compile, why("compile classpath"))
    }

    @Test
    fun `vendor module names the modules that exist when the name is wrong`() {
        createSdk(DistributorSettings(slimJars = true, categories = categories))
        gradle(sdkDir, "generateVendorIvyRepo")
        createApp(listOf(Dep.Module("no-such-module")))

        val output = gradleFails(appDir, "printClasspaths").output

        assertTrue("no module 'no-such-module'" in output, why(output))
        assertTrue("lv.brain.vendor.client:demo-client" in output, why("the message lists what is available:\n$output"))
    }

    @Test
    fun `dependencies for subprojects declared in the root build script`() {
        createSdk(compileSplit)
        gradle(sdkDir, "generateVendorIvyRepo")
        val notation = "lv.brain.vendor.bundle:server:2026.1.0"
        val rootBlock = if (dsl == Dsl.GROOVY) {
            "\nsubprojects {\n    dependencies {\n        implementation '$notation'\n    }\n}\n"
        } else {
            "\nsubprojects {\n    dependencies {\n        add(\"implementation\", \"$notation\")\n    }\n}\n"
        }
        createApp(emptyList(), includes = listOf("service"), buildScriptExtra = rootBlock)
        File(appDir, "service").mkdirs()
        File(appDir, "service/" + dsl.buildFile).writeText(
            if (dsl == Dsl.GROOVY) {
                """
                def compileCp = configurations.compileClasspath
                tasks.register('printClasspaths') { doLast { println 'COMPILE=' + compileCp.files*.name.sort() } }
                """.trimIndent()
            } else {
                """
                val compileCp = configurations.getByName("compileClasspath")
                tasks.register("printClasspaths") { doLast { println("COMPILE=" + compileCp.files.map { it.name }.sorted()) } }
                """.trimIndent()
            } + "\n"
        )

        val compile = classpath(gradle(appDir, ":service:printClasspaths").output, "COMPILE")

        assertEquals(listOf("demo-client-1.0.0-slim.jar", "demo-server-1.0.0-slim.jar", "leak-1.0.0-slim.jar"), compile, why("compile classpath of the subproject"))
    }

    @Test
    fun `a dependency that clashes with a runtime only vendor jar fails the build until it is ignored`() {
        createSdk(compileSplit)
        gradle(sdkDir, "generateVendorIvyRepo")
        createApp(listOf(Dep.Bundle("client"), Dep.Gav("org.lite:commons-lite:3.7")), localMavenRepo = true)
        Scripts.writeLocalMavenLite(appDir, "3.7")

        val failed = gradleFails(appDir, "compileJava")

        assertEquals(TaskOutcome.FAILED, failed.task(":verifyVendorClasspath")?.outcome, why(failed.output))
        assertTrue("org.lite:commons-lite" in failed.output && "3.7" in failed.output && "3.8" in failed.output, why(failed.output))

        File(appDir, "vendor.local.properties").appendText("vendor.clashIgnore=org.lite:*\n")
        val passed = gradle(appDir, "compileJava")
        assertEquals(TaskOutcome.SUCCESS, passed.task(":compileJava")?.outcome, why(passed.output))
    }
}

@Tag("gradle-matrix")
class GroovyDslParityTest : DslParityTest(Dsl.GROOVY)

@Tag("gradle-matrix")
class KotlinDslParityTest : DslParityTest(Dsl.KOTLIN)
