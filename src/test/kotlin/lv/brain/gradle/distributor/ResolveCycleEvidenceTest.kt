/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 *
 * Copyright (c) 2026 Edgars Grinvalds <edgars.grinvalds@brain.lv>
 */

package lv.brain.gradle.distributor

import groovy.json.JsonSlurper
import org.gradle.testfixtures.ProjectBuilder
import org.gradle.testkit.runner.GradleRunner
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.objectweb.asm.ClassWriter
import org.objectweb.asm.Opcodes
import java.io.File
import java.util.jar.JarEntry
import java.util.jar.JarOutputStream

// When a cycle between vendor jars is broken, the graph file names the cycle, the removed edge and the classes
// (or pom entries) that created each edge, so the cross reference can be found and fixed.
class ResolveCycleEvidenceTest {

    @TempDir
    lateinit var tempDir: File

    @Test
    fun `broken cycle is reported with the classes that reference each other`() {
        val vendorDir = File(tempDir, "vendor").apply { mkdirs() }
        // alpha.A extends beta.B, and beta.B has a field of type alpha.A: the two jars depend on each other
        jar(File(vendorDir, "alpha-1.0.0.jar"), "com/alpha/A" to clazz("com/alpha/A", superName = "com/beta/B"))
        jar(File(vendorDir, "beta-1.0.0.jar"), "com/beta/B" to clazz("com/beta/B", fieldType = "Lcom/alpha/A;"))

        val cycles = resolve(vendorDir)

        assertEquals(1, cycles.size)
        val cycle = cycles.single()
        assertEquals(listOf("alpha", "beta", "alpha"), cycle["cycle"])
        assertEquals(mapOf("from" to "alpha", "to" to "beta"), cycle["removedEdge"])

        val edges = (cycle["edges"] as List<*>).map { it as Map<*, *> }
        val alphaToBeta = edges.single { it["from"] == "alpha" && it["to"] == "beta" }
        val betaToAlpha = edges.single { it["from"] == "beta" && it["to"] == "alpha" }
        assertEquals(true, alphaToBeta["removed"])
        assertEquals(false, betaToAlpha["removed"])
        assertEquals(listOf("com.alpha.A references com.beta.B"), alphaToBeta["evidence"])
        assertEquals(listOf("com.beta.B references com.alpha.A"), betaToAlpha["evidence"])
    }

    @Test
    fun `a class defined in two jars is attributed to the first jar`() {
        val vendorDir = File(tempDir, "vendor").apply { mkdirs() }
        // shaded-1.0.0.jar carries a copy of com.util.Util, which util-1.0.0.jar also defines. A reference to that class
        // is attributed to the first jar in path order (shaded), not to util, even though util is the real owner.
        jar(File(vendorDir, "shaded-1.0.0.jar"),
            "com/util/Util" to clazz("com/util/Util"),
            "com/shaded/Thing" to clazz("com/shaded/Thing"))
        jar(File(vendorDir, "util-1.0.0.jar"),
            "com/util/Util" to clazz("com/util/Util"),
            "com/util/User" to clazz("com/util/User", fieldType = "Lcom/shaded/Thing;"))
        jar(File(vendorDir, "zzz-1.0.0.jar"),
            "com/zzz/Z" to clazz("com/zzz/Z", superName = "com/util/Util"))

        val cycles = resolve(vendorDir)
        assertTrue(cycles.isEmpty(), "no cycle expected: $cycles")

        val graph = JsonSlurper().parse(File(tempDir, "build/module-dependencies.json")) as Map<*, *>
        val modules = (graph["modules"] as List<*>).map { it as Map<*, *> }
        val zzz = modules.single { it["moduleId"] == "zzz" }
        val dependsOn = (zzz["dependsOn"] as List<*>).map { (it as Map<*, *>)["moduleId"] }
        assertEquals(listOf("shaded"), dependsOn)
    }

    @Test
    fun `the build log names the cycle, the classes and the duplicate definition`() {
        val vendorDir = File(tempDir, "vendor").apply { mkdirs() }
        jar(File(vendorDir, "alpha-1.0.0.jar"), "com/alpha/A" to clazz("com/alpha/A", superName = "com/beta/B"))
        jar(File(vendorDir, "beta-1.0.0.jar"), "com/beta/B" to clazz("com/beta/B", fieldType = "Lcom/alpha/A;"))
        // a second jar that defines com.beta.B again
        jar(File(vendorDir, "beta-copy-1.0.0.jar"), "com/beta/B" to clazz("com/beta/B"))
        File(tempDir, "vendor.properties").writeText("vendor.version=2026.1.0\n")
        File(tempDir, "build.gradle.kts").writeText("")
        File(tempDir, "settings.gradle.kts").writeText(
            "plugins { id(\"lv.brain.vendor-distributor\") }\nrootProject.name = \"cycle-demo\"\n"
        )

        val output = GradleRunner.create()
            .withProjectDir(tempDir)
            .withPluginClasspath()
            .withArguments("resolveVendorModules")
            .build()
            .output

        assertTrue(output.contains("Breaking circular vendor JAR dependency: alpha -> beta"), output)
        assertTrue(output.contains("cycle: alpha -> beta -> alpha"), output)
        assertTrue(output.contains("com.beta.B references com.alpha.A"), output)
        assertTrue(
            output.contains("com.alpha.A references com.beta.B (that class is defined in several jars: beta, beta-copy"),
            output
        )
    }

    private fun resolve(vendorDir: File): List<Map<*, *>> {
        val project = ProjectBuilder.builder().withProjectDir(tempDir).build()
        val task = project.tasks.register("resolveDeps", ResolveModuleDependenciesTask::class.java) {
            this.vendorDir.set(vendorDir)
            this.dependencyGraphFile.set(File(tempDir, "build/module-dependencies.json"))
        }.get()
        task.resolveDependencies()
        val graph = JsonSlurper().parse(task.dependencyGraphFile.get().asFile) as Map<*, *>
        return (graph["brokenCycles"] as List<*>).map { it as Map<*, *> }
    }

    private fun clazz(internalName: String, superName: String = "java/lang/Object", fieldType: String? = null): ByteArray {
        val cw = ClassWriter(0)
        cw.visit(Opcodes.V17, Opcodes.ACC_PUBLIC, internalName, null, superName, null)
        if (fieldType != null) cw.visitField(Opcodes.ACC_PUBLIC, "ref", fieldType, null, null).visitEnd()
        cw.visitEnd()
        return cw.toByteArray()
    }

    private fun jar(target: File, vararg classes: Pair<String, ByteArray>) {
        JarOutputStream(target.outputStream()).use { jos ->
            for ((internalName, bytes) in classes) {
                jos.putNextEntry(JarEntry("$internalName.class"))
                jos.write(bytes)
                jos.closeEntry()
            }
        }
    }
}
