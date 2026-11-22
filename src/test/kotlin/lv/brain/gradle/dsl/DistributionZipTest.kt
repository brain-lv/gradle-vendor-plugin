/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 *
 * Copyright (c) 2026 Edgars Grinvalds <edgars.grinvalds@brain.lv>
 */

package lv.brain.gradle.dsl

import lv.brain.gradle.support.GradleEnv
import lv.brain.gradle.support.SyntheticJars
import lv.brain.gradle.support.classpath
import lv.brain.gradle.support.zipEntries
import org.gradle.testkit.runner.TaskOutcome
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

/** What goes into `vendor-sdk-<version>.zip` and the optional `-slim.zip`, and how a consumer keeps both apart. */
@Tag("gradle-matrix")
class DistributionZipTest {

    @TempDir
    lateinit var tmp: File

    private val sdk get() = File(tmp, "sdk")

    private fun zip(name: String) = zipEntries(File(sdk, "build/distributions/$name"))

    private fun createSdk(vararg properties: String, settingsBlock: String = "") {
        SyntheticJars.write(File(sdk, "vendor/client/lib/api-1.0.jar"), mapOf("com/acme/Api.class" to SyntheticJars.clazz("com/acme/Api")))
        SyntheticJars.write(File(sdk, "vendor/tools/unmatched-1.0.jar"), mapOf("t/T.class" to SyntheticJars.clazz("t/T")))
        File(sdk, "vendor/docs/readme.txt").apply { parentFile.mkdirs() }.writeText("read me\n")
        File(sdk, "vendor/docs/internal/secret.txt").apply { parentFile.mkdirs() }.writeText("secret\n")
        File(sdk, "vendor/.gradle/state.bin").apply { parentFile.mkdirs() }.writeText("x")
        File(sdk, "vendor/.DS_Store").writeText("x")
        File(sdk, "vendor.properties").writeText((listOf("vendor.version=2026.1.0", "vendor.category.client=client") + properties).joinToString("\n") + "\n")
        File(sdk, "settings.gradle.kts").writeText(
            "plugins { id(\"lv.brain.vendor-distributor\") }\nrootProject.name = \"vendor-sdk\"\n$settingsBlock"
        )
        File(sdk, "build.gradle.kts").writeText("")
    }

    @Test
    fun `the distribution holds every file of the vendor folder except excluded and hidden ones`() {
        createSdk("vendor.zip.exclude=docs/internal/**")

        val first = GradleEnv.build(sdk, "packageVendorDistribution")
        val files = zip("vendor-sdk-2026.1.0.zip").keys

        listOf("module-dependencies.json", "client/lib/api-1.0.jar", "tools/unmatched-1.0.jar", "docs/readme.txt", "ivy/vendor-layout.properties")
            .forEach { assertTrue(it in files, "$it missing: $files") }
        assertTrue(files.none { "secret" in it || ".gradle" in it || ".DS_Store" in it }, "$files")
        assertTrue(files.none { it.startsWith("ivy/") && "unmatched" in it }, "a jar of no category became a module: $files")
        assertNull(first.task(":packageVendorSlimDistribution"), first.output)
        assertTrue(!File(sdk, "build/distributions/vendor-sdk-2026.1.0-slim.zip").exists())

        File(sdk, "vendor/docs/readme.txt").writeText("changed\n")
        val second = GradleEnv.build(sdk, "packageVendorDistribution")
        assertEquals(TaskOutcome.UP_TO_DATE, second.task(":resolveVendorModules")?.outcome, second.output)
        assertEquals(TaskOutcome.SUCCESS, second.task(":packageVendorDistribution")?.outcome, second.output)
        assertEquals("changed\n", String(zip("vendor-sdk-2026.1.0.zip").getValue("docs/readme.txt")))
    }

    @Test
    fun `the settings block chooses the files and the slim zip holds only the category jars and its own includes`() {
        createSdk(
            settingsBlock = """
                vendorDistributor {
                    slimZip.set(true)
                    distributionZip { include("client", "docs"); exclude("docs/internal") }
                    slimDistributionZip { include("docs/readme.txt") }
                }
            """.trimIndent() + "\n"
        )

        val result = GradleEnv.build(sdk, "packageVendorDistribution")

        assertEquals(TaskOutcome.SUCCESS, result.task(":packageVendorSlimDistribution")?.outcome, result.output)
        val full = zip("vendor-sdk-2026.1.0.zip").keys
        assertTrue("docs/readme.txt" in full && "client/lib/api-1.0.jar" in full, "$full")
        assertTrue("tools/unmatched-1.0.jar" !in full && "docs/internal/secret.txt" !in full, "$full")
        val slim = zip("vendor-sdk-2026.1.0-slim.zip").keys
        assertEquals(
            setOf("client/lib/api-1.0.jar", "docs/readme.txt", "module-dependencies.json", "vendor-sbom.cdx.json"),
            slim.filterNot { it.startsWith("ivy/") }.toSet()
        )
        assertTrue(slim.any { it.startsWith("ivy/") && it.endsWith("api-1.0.module") }, "$slim")
    }

    @Test
    fun `a consumer unpacks the full and the slim zip of one version into separate folders`() {
        createSdk("vendor.slimZip=true")
        GradleEnv.build(sdk, "packageVendorDistribution")

        val app = File(tmp, "app").apply { mkdirs() }
        File(app, "settings.gradle.kts").writeText("plugins { id(\"lv.brain.vendor-consumer\") }\nrootProject.name = \"app\"\n")
        File(app, "build.gradle.kts").writeText(
            """
            plugins { java }
            dependencies { implementation(vendor.bundle("client")) }
            tasks.register("printClasspaths") {
                val runtimeFiles = configurations.runtimeClasspath.get().incoming.files
                doLast { println("RUNTIME=" + runtimeFiles.files.map { it.name }.sorted()) }
            }
            """.trimIndent() + "\n"
        )
        for (name in listOf("vendor-sdk-2026.1.0-slim.zip", "vendor-sdk-2026.1.0.zip")) {
            File(app, "vendor.properties").writeText(
                "vendor.version=2026.1.0\nvendor.cacheMode=project\nvendor.localZip=../sdk/build/distributions/$name\n"
            )
            val output = GradleEnv.build(app, "printClasspaths").output
            assertEquals(listOf("api-1.0.jar"), classpath(output, "RUNTIME"), output)
        }

        assertTrue(File(app, ".vendor/2026.1.0-slim/client/lib/api-1.0.jar").isFile)
        assertTrue(!File(app, ".vendor/2026.1.0-slim/docs/readme.txt").exists())
        assertTrue(File(app, ".vendor/2026.1.0/docs/readme.txt").isFile)
    }
}
