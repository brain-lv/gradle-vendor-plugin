/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 *
 * Copyright (c) 2026 Edgars Grinvalds <edgars.grinvalds@brain.lv>
 */

package lv.brain.gradle.dsl

import lv.brain.gradle.support.Dep
import lv.brain.gradle.support.DemoSdk
import lv.brain.gradle.support.DistributorSettings
import lv.brain.gradle.support.Dsl
import lv.brain.gradle.support.GradleEnv
import lv.brain.gradle.support.PluginTestBase
import lv.brain.gradle.support.ZipServer
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import java.io.File

/**
 * Both plugins with Isolated Projects switched on. Gradle then fails the build for every project that reads or changes
 * another project (`allprojects`, `subprojects`, `rootProject.tasks`, ...) and for every callback that is not isolated,
 * so a plain successful build is the proof; the second run proves that the configuration cache entry is reusable, which is
 * what Isolated Projects is for. A build with several projects is used, because a build with one project has nothing to
 * be isolated from.
 */
abstract class IsolatedProjectsTest(dsl: Dsl) : PluginTestBase(dsl) {

    private val categories = mapOf("client" to listOf("client/lib/**"), "server" to listOf("server/lib"))
    private val compileSplit = DistributorSettings(slimJars = true, compilePackages = listOf("com.demo"), categories = categories)
    private val isolated = GradleEnv.isolatedProjectsFlag

    // ---- distributor ----

    @Test
    fun `a distributor build with several projects runs with isolated projects and reuses its configuration cache`() {
        createSdk(compileSplit)
        File(sdkDir, "extra").mkdirs()
        File(sdkDir, dsl.settingsFile).appendText(if (dsl == Dsl.GROOVY) "\ninclude 'extra'\n" else "\ninclude(\"extra\")\n")
        // every project gets the project plugin: the bundle configurations are created in it, after its script ran
        File(sdkDir, "extra/" + dsl.buildFile).writeText(
            if (dsl == Dsl.GROOVY) {
                "afterEvaluate { println 'EXTRA_HAS_VENDOR_CLIENT=' + configurations.names.contains('vendorClient') }\n"
            } else {
                "afterEvaluate { println(\"EXTRA_HAS_VENDOR_CLIENT=\" + configurations.names.contains(\"vendorClient\")) }\n"
            }
        )

        val first = gradle(sdkDir, "packageVendorDistribution", isolated)
        assertTrue("EXTRA_HAS_VENDOR_CLIENT=true" in first.output, why(first.output))
        assertTrue("Configuration cache entry stored" in first.output, why(first.output))
        assertTrue(distribution().keys.any { it.endsWith("client-2026.1.0.module") }, why(distribution().keys))

        // The plugin creates .git/info/exclude while configuring the first time, through a value source, so the entry stays
        // valid; the second and the third run both reuse it.
        gradle(sdkDir, "packageVendorDistribution", isolated)
        val third = gradle(sdkDir, "packageVendorDistribution", isolated)
        assertTrue("Reusing configuration cache" in third.output, why(third.output))
        assertTrue("Configuration cache entry reused" in third.output, why(third.output))
    }

    @Test
    fun `category includes and a default category work with isolated projects`() {
        // category includes are what the DSL offers for isolated projects: they are plain values
        createSdk(compileSplit.copy(defaultCategory = "misc"))

        val result = gradle(sdkDir, "packageVendorDistribution", isolated)

        assertTrue("Configuration cache entry stored" in result.output, why(result.output))
        assertTrue(distribution().keys.any { it.endsWith("misc-2026.1.0.module") }, why(distribution().keys))
    }

    // ---- consumer ----

    private fun writeSubproject(name: String, notation: String) {
        File(appDir, name).mkdirs()
        File(appDir, "$name/" + dsl.buildFile).writeText(
            if (dsl == Dsl.GROOVY) {
                """
                dependencies { implementation '$notation' }
                tasks.register('printClasspaths') {
                    def compileFiles = configurations.compileClasspath.incoming.files
                    def runtimeFiles = configurations.runtimeClasspath.incoming.files
                    def report = layout.buildDirectory.file('classpaths.txt')
                    doLast {
                        def lines = ['$name COMPILE=' + compileFiles.files*.name.sort(), '$name RUNTIME=' + runtimeFiles.files*.name.sort()]
                        report.get().asFile.with { parentFile.mkdirs(); text = lines.join('\n') + '\n' }
                        lines.each { println it }
                    }
                }
                """.trimIndent()
            } else {
                """
                dependencies { add("implementation", "$notation") }
                tasks.register("printClasspaths") {
                    val compileFiles = configurations.compileClasspath.get().incoming.files
                    val runtimeFiles = configurations.runtimeClasspath.get().incoming.files
                    val report = layout.buildDirectory.file("classpaths.txt")
                    doLast {
                        val lines = listOf("$name COMPILE=" + compileFiles.files.map { it.name }.sorted(), "$name RUNTIME=" + runtimeFiles.files.map { it.name }.sorted())
                        report.get().asFile.apply { parentFile.mkdirs(); writeText(lines.joinToString("\n") + "\n") }
                        lines.forEach { println(it) }
                    }
                }
                """.trimIndent()
            } + "\n"
        )
    }

    private fun printed(output: String, project: String, kind: String): List<String> {
        val report = File(appDir, "$project/build/classpaths.txt").takeIf { it.isFile }?.readText().orEmpty()
        val line = output.lineSequence().firstOrNull { it.startsWith("$project $kind=") }
            ?: report.lineSequence().firstOrNull { it.startsWith("$project $kind=") }
            ?: error("no '$project $kind=' line in the build output:\n$output")
        return line.removePrefix("$project $kind=").removeSurrounding("[", "]").split(", ").filter { it.isNotBlank() }
    }

    @Test
    fun `projects of a consumer build declare vendor dependencies in their own scripts under isolated projects`() {
        createSdk(compileSplit)
        gradle(sdkDir, "generateVendorIvyRepo")
        createApp(emptyList(), includes = listOf("service", "worker"))
        writeSubproject("service", "lv.brain.vendor.bundle:server:2026.1.0")
        writeSubproject("worker", "lv.brain.vendor.bundle:client:2026.1.0")

        val first = gradle(appDir, "service:printClasspaths", "worker:printClasspaths", isolated)

        assertTrue("Configuration cache entry stored" in first.output, why(first.output))
        assertEquals(
            listOf("demo-client-1.0.0-slim.jar", "demo-server-1.0.0-slim.jar", "leak-1.0.0-slim.jar"),
            printed(first.output, "service", "COMPILE"), why("compile classpath of service")
        )
        assertEquals(
            listOf("commons-lite-3.8.jar", "demo-client-1.0.0.jar", "demo-server-1.0.0.jar", "leak-1.0.0.jar"),
            printed(first.output, "service", "RUNTIME"), why("runtime classpath of service")
        )
        assertEquals(
            listOf("demo-client-1.0.0-slim.jar", "leak-1.0.0-slim.jar"),
            printed(first.output, "worker", "COMPILE"), why("compile classpath of worker")
        )

        val second = gradle(appDir, "service:printClasspaths", "worker:printClasspaths", isolated)
        assertTrue("Reusing configuration cache" in second.output, why(second.output))
        assertEquals(printed(first.output, "service", "COMPILE"), printed(second.output, "service", "COMPILE"), why("service, from the cache"))
        assertEquals(printed(first.output, "worker", "RUNTIME"), printed(second.output, "worker", "RUNTIME"), why("worker, from the cache"))
    }

    @Test
    fun `a distribution downloaded over http is unpacked once for all projects under isolated projects`() {
        createSdk(compileSplit)
        gradle(sdkDir, "packageVendorDistribution")
        val zip = File(sdkDir, "build/distributions/vendor-sdk-${DemoSdk.VERSION}.zip")

        ZipServer(zip, "/dist/vendor-sdk-${DemoSdk.VERSION}.zip").use { server ->
            createApp(
                emptyList(), includes = listOf("service", "worker"),
                localProperties = listOf("vendor.url=${server.urlPattern}", "vendor.cacheMode=project")
            )
            writeSubproject("service", "lv.brain.vendor.bundle:server:2026.1.0")
            writeSubproject("worker", "lv.brain.vendor.bundle:client:2026.1.0")

            val first = gradle(appDir, "service:printClasspaths", "worker:printClasspaths", isolated)

            assertEquals(1, server.getCount, why("the zip is downloaded once for both projects: ${server.requests}"))
            assertEquals(3, printed(first.output, "service", "COMPILE").size, why(first.output))
            assertEquals(2, printed(first.output, "worker", "COMPILE").size, why(first.output))

            // The first build downloads and unpacks a version it did not have, which makes its entry out of date once; the
            // entry stored by the second build is reused, and no later build asks the server again.
            val requestsAfterFirstBuild = server.requests.size
            gradle(appDir, "service:printClasspaths", "worker:printClasspaths", isolated)
            val third = gradle(appDir, "service:printClasspaths", "worker:printClasspaths", isolated)
            assertTrue("Reusing configuration cache" in third.output, why(third.output))
            assertEquals(requestsAfterFirstBuild, server.requests.size, why("a later build went to the server again: ${server.requests}"))
        }
    }

    @Test
    fun `a project that applies the vendor project plugin itself is not prepared twice`() {
        createSdk(compileSplit)
        gradle(sdkDir, "generateVendorIvyRepo")
        createApp(listOf(Dep.Bundle("server")), includes = listOf("service"))
        writeSubproject("service", "lv.brain.vendor.bundle:server:2026.1.0")

        val result = gradle(appDir, "printClasspaths", "service:printClasspaths", isolated)

        assertEquals(
            listOf("demo-client-1.0.0-slim.jar", "demo-server-1.0.0-slim.jar", "leak-1.0.0-slim.jar"),
            classpathOf(result.output, "COMPILE"), why("root project, which applies the plugin in its own script")
        )
        assertEquals(3, printed(result.output, "service", "COMPILE").size, why(result.output))
    }

    private fun classpathOf(output: String, prefix: String): List<String> =
        lv.brain.gradle.support.classpath(output, prefix)
}

@Tag("gradle-matrix")
class GroovyIsolatedProjectsTest : IsolatedProjectsTest(Dsl.GROOVY)

@Tag("gradle-matrix")
class KotlinIsolatedProjectsTest : IsolatedProjectsTest(Dsl.KOTLIN)
