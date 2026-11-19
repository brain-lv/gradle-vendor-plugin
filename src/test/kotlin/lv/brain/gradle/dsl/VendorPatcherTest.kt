/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 *
 * Copyright (c) 2026 brain-lv (https://github.com/brain-lv)
 */

package lv.brain.gradle.dsl

import lv.brain.gradle.support.Dsl
import lv.brain.gradle.support.DistributorSettings
import lv.brain.gradle.support.PluginTestBase
import lv.brain.gradle.support.SyntheticJars
import org.gradle.testkit.runner.TaskOutcome
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import java.io.File
import java.util.zip.ZipFile
import java.util.zip.ZipInputStream

/**
 * The `lv.brain.vendor-patcher` plugin on its own and applied by the distributor: numbered patches change a text file
 * inside a vendor jar, the distribution carries the patched jar, the vendor folder stays as it was.
 */
abstract class VendorPatcherTest(dsl: Dsl) : PluginTestBase(dsl) {

    private val categories = mapOf("client" to listOf("client/lib/**"), "server" to listOf("server/lib"))
    private val jarPath = "client/lib/config-1.0.0.jar"
    private val original = "name=demo\ntimeout=30\nretries=3\n"

    private fun patch(timeoutFrom: String = "30") = """
        --- a/$jarPath!/META-INF/app.properties
        +++ b/$jarPath!/META-INF/app.properties
        @@ -1,3 +1,3 @@
         name=demo
        -timeout=$timeoutFrom
        +timeout=60
         retries=3
    """.trimIndent() + "\n"

    private fun sdkWithConfigJar(vararg properties: String) {
        createSdk(DistributorSettings(categories = categories), *properties)
        SyntheticJars.write(File(sdkDir, "vendor/$jarPath"), mapOf("META-INF/app.properties" to original.toByteArray()))
    }

    private fun writePatch(dir: String = "internal-patches", text: String = patch()) =
        File(sdkDir, "$dir/001-timeout.patch").apply { parentFile.mkdirs(); writeText(text) }

    private fun entryOfJar(jarBytes: ByteArray, entry: String): String = ZipInputStream(jarBytes.inputStream()).use { zip ->
        generateSequence { zip.nextEntry }.first { it.name == entry }
        zip.readBytes().decodeToString()
    }

    private fun entryOfJar(jar: File, entry: String): String =
        ZipFile(jar).use { it.getInputStream(it.getEntry(entry)).readBytes().decodeToString() }

    @Test
    fun `the patcher is off by default`() {
        sdkWithConfigJar()
        writePatch()

        val result = gradle(sdkDir, "packageVendorDistribution")

        assertEquals(null, result.task(":applyVendorPatches"), why(result.output))
        assertEquals(original, entryOfJar(distribution().getValue(jarPath), "META-INF/app.properties"), why("the jar was patched"))
    }

    @Test
    fun `with vendor patcher true the distribution carries the patched jar and the vendor folder stays unpatched`() {
        sdkWithConfigJar("vendor.patcher=true")
        writePatch()

        val first = gradle(sdkDir, "packageVendorDistribution")

        assertEquals(TaskOutcome.SUCCESS, first.task(":applyVendorPatches")?.outcome, why(first.output))
        assertEquals("name=demo\ntimeout=60\nretries=3\n", entryOfJar(distribution().getValue(jarPath), "META-INF/app.properties"))
        assertEquals(original, entryOfJar(File(sdkDir, "vendor/$jarPath"), "META-INF/app.properties"), why("the vendor folder was changed"))

        val second = gradle(sdkDir, "packageVendorDistribution")
        listOf(":applyVendorPatches", ":resolveVendorModules", ":generateVendorIvyRepo", ":packageVendorDistribution").forEach {
            assertEquals(TaskOutcome.UP_TO_DATE, second.task(it)?.outcome, why("$it\n${second.output}"))
        }
    }

    @Test
    fun `the patcher works with the configuration cache and a changed patch runs the steps again`() {
        sdkWithConfigJar("vendor.patcher=true")
        val patchFile = writePatch()
        val args = arrayOf("packageVendorDistribution", "--configuration-cache")

        assertTrue("Configuration cache entry stored" in gradle(sdkDir, *args).output)
        val reused = gradle(sdkDir, *args)
        assertTrue("Reusing configuration cache" in reused.output, why(reused.output))
        assertEquals(TaskOutcome.UP_TO_DATE, reused.task(":applyVendorPatches")?.outcome, why(reused.output))

        patchFile.writeText(patch().replace("+timeout=60", "+timeout=90"))
        val changed = gradle(sdkDir, *args)
        assertEquals(TaskOutcome.SUCCESS, changed.task(":applyVendorPatches")?.outcome, why(changed.output))
        assertEquals("name=demo\ntimeout=90\nretries=3\n", entryOfJar(distribution().getValue(jarPath), "META-INF/app.properties"))
    }

    @Test
    fun `the patcher can be switched on in the settings block with its own patches folder`() {
        sdkWithConfigJar()
        writePatch(dir = "my-patches")
        val block = if (dsl == Dsl.GROOVY) {
            "vendorDistributor {\n    patcher = true\n    patchesDir = new File(rootDir, 'my-patches')\n}\n"
        } else {
            "vendorDistributor {\n    patcher.set(true)\n    patchesDir.set(File(rootDir, \"my-patches\"))\n}\n"
        }
        File(sdkDir, dsl.settingsFile).appendText("\n$block")

        gradle(sdkDir, "packageVendorDistribution")

        assertEquals("name=demo\ntimeout=60\nretries=3\n", entryOfJar(distribution().getValue(jarPath), "META-INF/app.properties"))
    }

    @Test
    fun `a failing step stops the build with the step, the file, the cause and the fix`() {
        sdkWithConfigJar("vendor.patcher=true")
        writePatch(text = patch(timeoutFrom = "45"))

        val result = gradleFails(sdkDir, "packageVendorDistribution")

        listOf(
            "Vendor patch step 1 of 1 failed: 001-timeout.patch",
            "File: $jarPath!/META-INF/app.properties",
            "Likely cause: The file changed since the patch was made",
            "How to fix:",
            "Nothing was written"
        ).forEach { assertTrue(it in result.output, why("missing '$it' in:\n${result.output}")) }
        assertEquals(null, result.task(":packageVendorDistribution")?.outcome, why(result.output))
    }

    @Test
    fun `the standalone plugin is taken from the build cache in a copy at another path`() {
        val project = File(tmp, "patched-project")
        SyntheticJars.write(File(project, "vendor/$jarPath"), mapOf("META-INF/app.properties" to original.toByteArray()))
        File(project, "internal-patches/001-timeout.patch").apply { parentFile.mkdirs(); writeText(patch()) }
        val cacheDir = File(tmp, "build-cache").invariantSeparatorsPath
        File(project, dsl.settingsFile).writeText(
            if (dsl == Dsl.GROOVY) "rootProject.name = 'patched'\nbuildCache { local { directory = new File('$cacheDir') } }\n"
            else "rootProject.name = \"patched\"\nbuildCache { local { directory = File(\"$cacheDir\") } }\n"
        )
        File(project, dsl.buildFile).writeText(
            if (dsl == Dsl.GROOVY) "plugins { id 'lv.brain.vendor-patcher' }\n" else "plugins { id(\"lv.brain.vendor-patcher\") }\n"
        )

        val first = gradle(project, "applyVendorPatches", "--build-cache")
        assertEquals(TaskOutcome.SUCCESS, first.task(":applyVendorPatches")?.outcome, why(first.output))
        assertEquals("name=demo\ntimeout=60\nretries=3\n", entryOfJar(File(project, "build/vendor-patched/vendor/$jarPath"), "META-INF/app.properties"))

        val copy = File(tmp, "elsewhere/patched-copy")
        project.copyRecursively(copy)
        File(copy, "build").deleteRecursively()
        File(copy, ".gradle").deleteRecursively()
        val second = gradle(copy, "applyVendorPatches", "--build-cache")

        assertEquals(TaskOutcome.FROM_CACHE, second.task(":applyVendorPatches")?.outcome, why(second.output))
        assertEquals("name=demo\ntimeout=60\nretries=3\n", entryOfJar(File(copy, "build/vendor-patched/vendor/$jarPath"), "META-INF/app.properties"))
    }
}

@Tag("gradle-matrix")
class GroovyVendorPatcherTest : VendorPatcherTest(Dsl.GROOVY)

@Tag("gradle-matrix")
class KotlinVendorPatcherTest : VendorPatcherTest(Dsl.KOTLIN)
