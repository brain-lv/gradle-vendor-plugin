/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 *
 * Copyright (c) 2026 brain-lv (https://github.com/brain-lv)
 */

package lv.brain.gradle.distributor

import org.gradle.testkit.runner.GradleRunner
import org.gradle.testkit.runner.TaskOutcome
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.objectweb.asm.ClassWriter
import org.objectweb.asm.Opcodes
import java.io.File
import java.util.jar.JarEntry
import java.util.jar.JarOutputStream
import java.util.zip.ZipFile

/**
 * Needs no network: all jars are generated locally.
 * Guards the two behaviours that matter for IDE import: nothing is scanned while Gradle configures the
 * build, and only jars selected by the category includes are packaged.
 */
class VendorDistributorLazinessTest {

    @TempDir
    lateinit var projectDir: File

    @Test
    fun `configuring the build does not touch the vendor jars but the packaging task selects them by include`() {
        File(projectDir, ".git").mkdirs()
        writeJar(File(projectDir, "vendor/client/lib/example-json-2.11.0.jar"), "org/example/json/Json")
        writeJar(File(projectDir, "vendor/client/lib/example-log-api-2.0.16.jar"), "org/example/log/Logger")
        writeJar(File(projectDir, "vendor/server/lib/example-util-33.3.1-jre.jar"), "org/example/util/Util")
        writeJar(File(projectDir, "vendor/tools/lib/not-in-any-category-1.0.jar"), "tools/Leftover")

        File(projectDir, "vendor.properties").writeText("vendor.version=2026.1.0\n")
        File(projectDir, "build.gradle.kts").writeText("")
        File(projectDir, "settings.gradle.kts").writeText(
            """
            plugins {
                id("lv.brain.vendor-distributor")
            }
            rootProject.name = "vendor-sdk-distributor"

            vendorDistributor {
                slimJars.set(true)
                // patterns that java.nio glob could not match: any depth prefix and a plain directory
                category("client") { include("**/client/**") }
                category("server") { include("server/lib") }
            }
            """.trimIndent()
        )

        // 1. What an IDE sync does: configure, but run no vendor task
        runner("help").build()
        assertFalse(File(projectDir, "vendor/ivy").exists(), "no Ivy repository may be generated during configuration")
        assertFalse(File(projectDir, "build").exists(), "no task output may be produced during configuration")

        // 2. Running the packaging task does the work
        val first = runner("packageVendorDistribution").build()
        listOf(":resolveVendorModules", ":generateVendorIvyRepo", ":packageVendorDistribution").forEach {
            assertEquals(TaskOutcome.SUCCESS, first.task(it)?.outcome, it)
        }

        val zip = File(projectDir, "build/distributions/vendor-sdk-2026.1.0.zip")
        ZipFile(zip).use { zf ->
            val names = zf.entries().asSequence().map { it.name }.toSet()
            assertTrue("client/lib/example-json-2.11.0.jar" in names)
            assertTrue("client/lib/example-log-api-2.0.16.jar" in names)
            assertTrue("server/lib/example-util-33.3.1-jre.jar" in names)
            assertTrue(names.none { it.contains("not-in-any-category") }, "jars without a matching include must be left out: $names")
            assertTrue("ivy/lv.brain.vendor.bundle/client/2026.1.0/client-2026.1.0.module" in names)
            assertTrue("ivy/lv.brain.vendor.bundle/server/2026.1.0/server-2026.1.0.module" in names)
            assertFalse(names.any { it.startsWith("ivy/lv.brain.vendor.bundle/core/") }, "no catch-all bundle for left out jars")
        }

        // 3. Nothing changed, so nothing runs again
        val second = runner("packageVendorDistribution").build()
        listOf(":resolveVendorModules", ":generateVendorIvyRepo", ":packageVendorDistribution").forEach {
            assertEquals(TaskOutcome.UP_TO_DATE, second.task(it)?.outcome, it)
        }
    }

    private fun runner(vararg args: String): GradleRunner =
        GradleRunner.create()
            .withProjectDir(projectDir)
            .withPluginClasspath()
            .withArguments(*args, "--stacktrace")
            .forwardOutput()

    private fun writeJar(target: File, internalClassName: String) {
        target.parentFile.mkdirs()
        val cw = ClassWriter(0)
        cw.visit(Opcodes.V17, Opcodes.ACC_PUBLIC, internalClassName, null, "java/lang/Object", null)
        cw.visitEnd()
        JarOutputStream(target.outputStream()).use { jos ->
            jos.putNextEntry(JarEntry("$internalClassName.class"))
            jos.write(cw.toByteArray())
            jos.closeEntry()
        }
    }
}
