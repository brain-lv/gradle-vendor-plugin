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
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.objectweb.asm.ClassWriter
import org.objectweb.asm.Opcodes
import java.io.File
import java.util.jar.JarEntry
import java.util.jar.JarOutputStream
import java.util.zip.ZipFile

// The Groovy DSL (settings.gradle) must select jars inside category('x') { include '...' }. A closure that resolved
// include(...) to Gradle's project include would create bogus projects such as ':client/lib/**' instead.
class VendorDistributorGroovyDslTest {

    @TempDir
    lateinit var projectDir: File

    @Test
    fun `groovy settings script selects jars per category`() {
        File(projectDir, ".git").mkdirs()
        writeJar(File(projectDir, "vendor/client/lib/example-json-2.11.0.jar"), "org/example/json/Json")
        writeJar(File(projectDir, "vendor/client/shared/example-log-api-2.0.16.jar"), "org/example/log/Logger")
        writeJar(File(projectDir, "vendor/server/lib/example-util-33.3.1-jre.jar"), "org/example/util/Util")
        writeJar(File(projectDir, "vendor/tools/lib/not-in-any-category-1.0.jar"), "tools/Leftover")

        File(projectDir, "vendor.properties").writeText("vendor.version=2026.1.0\n")
        File(projectDir, "build.gradle").writeText("")
        File(projectDir, "settings.gradle").writeText(
            """
            plugins {
                id 'lv.brain.vendor-distributor'
            }
            rootProject.name = 'vendor-sdk-distributor'

            vendorDistributor {
                slimJars = true

                category('client') {
                    include 'client/lib/**', '**/shared/*.jar'
                }
                category('server') {
                    include 'server/lib'
                }
            }
            """.trimIndent()
        )

        val result = GradleRunner.create()
            .withProjectDir(projectDir)
            .withPluginClasspath()
            .withArguments("packageVendorDistribution", "--stacktrace")
            .forwardOutput()
            .build()

        assertEquals(TaskOutcome.SUCCESS, result.task(":packageVendorDistribution")?.outcome)

        ZipFile(File(projectDir, "build/distributions/vendor-sdk-2026.1.0.zip")).use { zf ->
            val names = zf.entries().asSequence().map { it.name }.toSet()
            assertTrue("client/lib/example-json-2.11.0.jar" in names, "$names")
            assertTrue("client/shared/example-log-api-2.0.16.jar" in names, "$names")
            assertTrue("server/lib/example-util-33.3.1-jre.jar" in names, "$names")
            assertTrue(names.none { it.contains("not-in-any-category") }, "$names")
            assertTrue("ivy/lv.brain.vendor.bundle/client/2026.1.0/client-2026.1.0.module" in names, "$names")
            assertTrue("ivy/lv.brain.vendor.bundle/server/2026.1.0/server-2026.1.0.module" in names, "$names")
        }
    }

    @Test
    fun `compile packages from the settings dsl split compile and runtime jars and the zip carries the layout marker`() {
        File(projectDir, ".git").mkdirs()
        writeJar(File(projectDir, "vendor/client/lib/example-json-2.11.0.jar"), "org/example/json/Json")
        writeJar(File(projectDir, "vendor/client/lib/example-log-api-2.0.16.jar"), "org/example/log/Logger")
        File(projectDir, "vendor.properties").writeText("vendor.version=2026.1.0\n")
        File(projectDir, "build.gradle").writeText("")
        File(projectDir, "settings.gradle").writeText(
            """
            plugins { id 'lv.brain.vendor-distributor' }
            rootProject.name = 'vendor-sdk-distributor'

            vendorDistributor {
                compilePackages = ['org.example.json']
                category('client') { include 'client/lib/**' }
            }
            """.trimIndent()
        )

        GradleRunner.create().withProjectDir(projectDir).withPluginClasspath()
            .withArguments("packageVendorDistribution", "--stacktrace").forwardOutput().build()

        ZipFile(File(projectDir, "build/distributions/vendor-sdk-2026.1.0.zip")).use { zf ->
            val marker = zf.getInputStream(zf.getEntry("ivy/vendor-layout.properties")).reader().readText()
            assertTrue("compileSplit=true" in marker, marker)

            val bundle = zf.getEntry("ivy/lv.brain.vendor.bundle/client/2026.1.0/client-2026.1.0.module")
            @Suppress("UNCHECKED_CAST")
            val variants = (groovy.json.JsonSlurper().parse(zf.getInputStream(bundle).reader()) as Map<String, Any?>)["variants"] as List<Map<String, Any?>>
            fun modules(variant: String) = (variants.single { it["name"] == variant }["dependencies"] as List<Map<String, Any?>>).map { it["module"] }
            assertEquals(listOf("example-json"), modules("apiElements"))
            assertEquals(listOf("example-json", "example-log-api"), modules("runtimeElements"))
        }
    }

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
