/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 *
 * Copyright (c) 2026 brain-lv (https://github.com/brain-lv)
 */

package lv.brain.gradle.dsl

import lv.brain.gradle.support.Dep
import lv.brain.gradle.support.DemoSdk
import lv.brain.gradle.support.Dsl
import lv.brain.gradle.support.DistributorSettings
import lv.brain.gradle.support.PluginTestBase
import lv.brain.gradle.support.classpath
import org.gradle.testkit.runner.BuildResult
import org.gradle.testkit.runner.TaskOutcome
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import java.io.File

/**
 * Build cache and configuration cache: the vendor tasks come from the cache wherever the inputs are the same (also in
 * another checkout at another path), run again as soon as an input changes, and produce the same bytes every time.
 * Every test uses its own build cache folder, so no test can feed another one.
 */
abstract class BuildCacheTest(dsl: Dsl) : PluginTestBase(dsl) {

    private val categories = mapOf("client" to listOf("client/lib/**"), "server" to listOf("server/lib"))
    private val compileSplit = DistributorSettings(slimJars = true, compilePackages = listOf("com.demo"), categories = categories)
    private val vendorTasks = listOf(":resolveVendorModules", ":generateVendorIvyRepo", ":packageVendorDistribution")
    private val zipPath = "build/distributions/vendor-sdk-${DemoSdk.VERSION}.zip"

    private fun useOwnBuildCache(dir: File) {
        val cacheDir = File(tmp, "build-cache").invariantSeparatorsPath
        val block = if (dsl == Dsl.GROOVY) {
            "buildCache { local { directory = new File('$cacheDir') } }"
        } else {
            "buildCache { local { directory = File(\"$cacheDir\") } }"
        }
        File(dir, dsl.settingsFile).appendText("\n$block\n")
    }

    private fun outcomes(result: BuildResult): Map<String, TaskOutcome?> = vendorTasks.associateWith { result.task(it)?.outcome }

    private fun assertOutcome(expected: TaskOutcome, result: BuildResult, tasks: List<String> = vendorTasks) {
        tasks.forEach { assertEquals(expected, result.task(it)?.outcome, why("$it in ${outcomes(result)}\n${result.output}")) }
    }

    private fun cleanCopy(from: File, to: File): File {
        from.copyRecursively(to)
        listOf("build", ".gradle", "vendor/ivy").forEach { File(to, it).deleteRecursively() }
        return to
    }

    @Test
    fun `the vendor tasks come from the build cache in a clean checkout at another path`() {
        createSdk(compileSplit)
        useOwnBuildCache(sdkDir)

        val first = gradle(sdkDir, "packageVendorDistribution", "--build-cache")
        assertOutcome(TaskOutcome.SUCCESS, first)

        val copy = cleanCopy(sdkDir, File(tmp, "elsewhere/sdk-copy"))
        val second = gradle(copy, "packageVendorDistribution", "--build-cache")

        assertOutcome(TaskOutcome.FROM_CACHE, second)
        assertArrayEquals(File(sdkDir, zipPath).readBytes(), File(copy, zipPath).readBytes(), why("the restored zip differs"))
        assertTrue(File(copy, "vendor/ivy").isDirectory, why("the Ivy repository was not restored into the vendor folder"))
    }

    @Test
    fun `the distribution is the same byte for byte when it is built again without any cache`() {
        createSdk(compileSplit)

        gradle(sdkDir, "packageVendorDistribution")
        val first = File(sdkDir, zipPath).readBytes()

        File(sdkDir, "build").deleteRecursively()
        File(sdkDir, "vendor/ivy").deleteRecursively()
        Thread.sleep(2_100)
        gradle(sdkDir, "packageVendorDistribution", "--rerun-tasks")

        assertArrayEquals(first, File(sdkDir, zipPath).readBytes(), why("the zip changed although nothing but the time did"))
    }

    @Test
    fun `a changed input runs the tasks again and the previous result comes back from the cache`() {
        createSdk(compileSplit)
        useOwnBuildCache(sdkDir)
        val properties = File(sdkDir, "vendor.properties")
        val original = properties.readText()

        assertOutcome(TaskOutcome.SUCCESS, gradle(sdkDir, "packageVendorDistribution", "--build-cache"))
        assertOutcome(TaskOutcome.UP_TO_DATE, gradle(sdkDir, "packageVendorDistribution", "--build-cache"))

        properties.appendText("vendor.dependencyExcludeClasses=org.leak\n")
        assertOutcome(TaskOutcome.SUCCESS, gradle(sdkDir, "packageVendorDistribution", "--build-cache"), listOf(":resolveVendorModules"))

        properties.writeText(original)
        assertOutcome(TaskOutcome.FROM_CACHE, gradle(sdkDir, "packageVendorDistribution", "--build-cache"))
    }

    @Test
    fun `a changed vendor jar runs the tasks again`() {
        createSdk(compileSplit)
        useOwnBuildCache(sdkDir)
        gradle(sdkDir, "packageVendorDistribution", "--build-cache")

        val jar = File(sdkDir, "vendor/client/lib").listFiles()!!.first { it.name.startsWith("demo-client") && it.extension == "jar" }
        jar.setLastModified(jar.lastModified() + 60_000)
        assertOutcome(TaskOutcome.UP_TO_DATE, gradle(sdkDir, "packageVendorDistribution", "--build-cache"))

        File(jar.parentFile, "extra-1.0.0.jar").writeBytes(jar.readBytes())
        val changed = gradle(sdkDir, "packageVendorDistribution", "--build-cache")
        assertOutcome(TaskOutcome.SUCCESS, changed, listOf(":resolveVendorModules"))
    }

    @Test
    fun `the distributor reuses its configuration cache from the second build on`() {
        createSdk(compileSplit)
        val args = arrayOf("packageVendorDistribution", "--configuration-cache")

        val stored = gradle(sdkDir, *args)
        assertTrue("Configuration cache entry stored" in stored.output, why(stored.output))
        assertTrue(File(sdkDir, ".git/info/exclude").readText().contains("/vendor.local.properties"), why("git exclude"))

        val second = gradle(sdkDir, *args)
        assertTrue("Reusing configuration cache" in second.output, why(second.output))
        assertOutcome(TaskOutcome.UP_TO_DATE, second)
    }

    @Test
    fun `a consumer reuses its configuration cache and picks up a rebuilt local zip of the same version`() {
        createSdk(compileSplit)
        gradle(sdkDir, "packageVendorDistribution")
        createApp(
            listOf(Dep.Bundle("server")),
            localProperties = listOf("vendor.localZip=../sdk/$zipPath", "vendor.cacheMode=project")
        )
        val args = arrayOf("printClasspaths", "--configuration-cache")

        val first = gradle(appDir, *args)
        assertTrue("Configuration cache entry stored" in first.output, why(first.output))
        val splitCompile = classpath(first.output, "COMPILE")

        val second = gradle(appDir, *args)
        assertTrue("Reusing configuration cache" in second.output, why(second.output))
        assertEquals(splitCompile, classpath(second.output, "COMPILE"), why(second.output))

        createSdk(DistributorSettings(slimJars = true, categories = categories))
        gradle(sdkDir, "packageVendorDistribution")

        val rebuilt = gradle(appDir, *args)
        assertNotEquals(splitCompile, classpath(rebuilt.output, "COMPILE"), why("the rebuilt zip was not unpacked:\n${rebuilt.output}"))
        assertTrue("commons-lite-3.8-slim.jar" in classpath(rebuilt.output, "COMPILE"), why(rebuilt.output))
    }
}

@Tag("gradle-matrix")
class GroovyBuildCacheTest : BuildCacheTest(Dsl.GROOVY)

@Tag("gradle-matrix")
class KotlinBuildCacheTest : BuildCacheTest(Dsl.KOTLIN)
