/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 *
 * Copyright (c) 2026 brain-lv (https://github.com/brain-lv)
 */

package lv.brain.gradle.dsl

import lv.brain.gradle.support.Dep
import lv.brain.gradle.support.DemoSdk
import lv.brain.gradle.support.DistributorSettings
import lv.brain.gradle.support.Dsl
import lv.brain.gradle.support.PluginTestBase
import lv.brain.gradle.support.SyntheticJars
import lv.brain.gradle.support.ZipServer
import lv.brain.gradle.support.classpath
import org.gradle.testkit.runner.TaskOutcome
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import java.io.File

/**
 * The whole product from the distributor to the application, in the build script language of the subclass, on the Gradle
 * that the `integrationTestGradle*` task selects. Only run there: they download a Gradle distribution and take longer
 * than the scenarios of [DslParityTest].
 */
abstract class PipelineIntegrationTest(dsl: Dsl) : PluginTestBase(dsl) {

    private val categories = mapOf("client" to listOf("client/lib/**"), "server" to listOf("server/lib"))
    private val compileSplit = DistributorSettings(slimJars = true, compilePackages = listOf("com.demo"), categories = categories)

    @Test
    fun `a distribution is downloaded over http once, cached, and the application compiles and runs on it`() {
        createSdk(compileSplit)
        val packaged = gradle(sdkDir, "packageVendorDistribution")
        assertEquals(TaskOutcome.SUCCESS, packaged.task(":packageVendorDistribution")?.outcome, why(packaged.output))
        val zip = File(sdkDir, "build/distributions/vendor-sdk-${DemoSdk.VERSION}.zip")

        ZipServer(zip, "/dist/vendor-sdk-${DemoSdk.VERSION}.zip").use { server ->
            createApp(
                listOf(Dep.Bundle("server")),
                localProperties = listOf("vendor.url=${server.urlPattern}", "vendor.cacheMode=project"),
                extraSource = "package app;\npublic class Extra { com.demo.client.Api api; Object leak() { return api.leak(); } }\n"
            )

            val first = gradle(appDir, "compileJava", "printClasspaths", "runApp").output

            assertEquals(1, server.getCount, why("the zip is downloaded once: ${server.requests}"))
            assertEquals(
                listOf("demo-client-1.0.0-slim.jar", "demo-server-1.0.0-slim.jar", "leak-1.0.0-slim.jar"),
                classpath(first, "COMPILE"), why("compile classpath")
            )
            assertEquals(
                listOf("commons-lite-3.8.jar", "demo-client-1.0.0.jar", "demo-server-1.0.0.jar", "leak-1.0.0.jar"),
                classpath(first, "RUNTIME"), why("runtime classpath")
            )
            listOf("com.demo.client.Api", "org.leak.Leak", "org.lite.Util").forEach {
                assertTrue("LOADED $it" in first, why("$it was not loaded at run time:\n$first"))
            }

            val requestsAfterFirstBuild = server.requests.size
            gradle(appDir, "runApp")
            assertEquals(requestsAfterFirstBuild, server.requests.size, why("the second build went to the server again: ${server.requests}"))
        }
    }

    @Test
    fun `the distributor works with the configuration cache and notices when its configuration changes`() {
        createSdk(DistributorSettings(slimJars = true, categories = categories))
        val args = arrayOf("packageVendorDistribution", "--configuration-cache")

        val stored = gradle(sdkDir, *args)
        assertTrue("Configuration cache entry stored" in stored.output, why(stored.output))

        // The plugin adds its entries to .git/info/exclude while the build is configured. When that creates or changes the file,
        // the entry that the same run stored is out of date, so the run after the first one configures once more. From
        // then on the file no longer changes and the entry has to be reused.
        gradle(sdkDir, *args)

        val reused = gradle(sdkDir, *args)
        assertTrue("Reusing configuration cache" in reused.output, why(reused.output))
        assertEquals(TaskOutcome.UP_TO_DATE, reused.task(":packageVendorDistribution")?.outcome, why(reused.output))

        // an entry that ignores a changed vendor.properties would silently build the old distribution
        File(sdkDir, "vendor.properties").appendText("vendor.compilePackages=com.demo\n")
        val changed = gradle(sdkDir, *args)
        assertTrue("Reusing configuration cache" !in changed.output, why("a stale configuration cache entry was reused:\n${changed.output}"))
        assertTrue(String(distribution().getValue("ivy/vendor-layout.properties")).contains("compileSplit=true"), why("the new setting did not reach the distribution"))
    }

    @Test
    fun `configuring the build reads no jars, a repeated run is up to date and a changed jar is noticed`() {
        createSdk(compileSplit)

        gradle(sdkDir, "help")
        assertTrue(!File(sdkDir, "build/vendor/module-dependencies.json").exists(), why("configuring the build scanned the vendor jars"))
        assertTrue(!File(sdkDir, "vendor/ivy").exists(), why("configuring the build generated the Ivy repository"))

        gradle(sdkDir, "packageVendorDistribution")
        val again = gradle(sdkDir, "packageVendorDistribution")
        listOf(":resolveVendorModules", ":generateVendorIvyRepo", ":packageVendorDistribution").forEach {
            assertEquals(TaskOutcome.UP_TO_DATE, again.task(it)?.outcome, why("$it on the second run:\n${again.output}"))
        }

        // another class in a jar that is packaged: nothing may be up to date any more
        SyntheticJars.write(
            File(sdkDir, "vendor/client/lib/leak-1.0.0.jar"),
            mapOf("org/leak/Leak.class" to SyntheticJars.clazz("org/leak/Leak"), "org/leak/More.class" to SyntheticJars.clazz("org/leak/More"))
        )
        val changed = gradle(sdkDir, "packageVendorDistribution")
        assertEquals(TaskOutcome.SUCCESS, changed.task(":resolveVendorModules")?.outcome, why(changed.output))
        assertEquals(TaskOutcome.SUCCESS, changed.task(":packageVendorDistribution")?.outcome, why(changed.output))
    }
}

@Tag("gradle-matrix-only")
class GroovyPipelineIntegrationTest : PipelineIntegrationTest(Dsl.GROOVY)

@Tag("gradle-matrix-only")
class KotlinPipelineIntegrationTest : PipelineIntegrationTest(Dsl.KOTLIN)
