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
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.objectweb.asm.ClassWriter
import org.objectweb.asm.Opcodes
import java.io.File

/** A Gradle plugin shipped as a vendor jar is applied with `plugins { id(...) }`, without a version or a buildscript block. */
@Tag("gradle-matrix")
class VendorPluginsTest {

    @TempDir
    lateinit var tmp: File

    private fun pluginClass(name: String, helper: String): ByteArray {
        val cw = ClassWriter(ClassWriter.COMPUTE_MAXS or ClassWriter.COMPUTE_FRAMES)
        cw.visit(Opcodes.V17, Opcodes.ACC_PUBLIC, name, null, "java/lang/Object", arrayOf("org/gradle/api/Plugin"))
        cw.visitMethod(Opcodes.ACC_PUBLIC, "<init>", "()V", null, null).apply {
            visitCode(); visitVarInsn(Opcodes.ALOAD, 0)
            visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false)
            visitInsn(Opcodes.RETURN); visitMaxs(0, 0); visitEnd()
        }
        cw.visitMethod(Opcodes.ACC_PUBLIC, "apply", "(Ljava/lang/Object;)V", null, null).apply {
            visitCode()
            visitFieldInsn(Opcodes.GETSTATIC, "java/lang/System", "out", "Ljava/io/PrintStream;")
            visitMethodInsn(Opcodes.INVOKESTATIC, helper, "message", "()Ljava/lang/String;", false)
            visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/io/PrintStream", "println", "(Ljava/lang/String;)V", false)
            visitInsn(Opcodes.RETURN); visitMaxs(0, 0); visitEnd()
        }
        cw.visitEnd()
        return cw.toByteArray()
    }

    private fun helperClass(name: String): ByteArray {
        val cw = ClassWriter(ClassWriter.COMPUTE_MAXS)
        cw.visit(Opcodes.V17, Opcodes.ACC_PUBLIC, name, null, "java/lang/Object", null)
        cw.visitMethod(Opcodes.ACC_PUBLIC or Opcodes.ACC_STATIC, "message", "()Ljava/lang/String;", null, null).apply {
            visitCode(); visitLdcInsn("VENDOR TOOL APPLIED"); visitInsn(Opcodes.ARETURN); visitMaxs(0, 0); visitEnd()
        }
        cw.visitEnd()
        return cw.toByteArray()
    }

    @Test
    fun `a vendor plugin jar is applied by id and stays out of the bundles`() {
        val sdk = File(tmp, "sdk")
        SyntheticJars.write(
            File(sdk, "vendor/tools/vendor-tool-1.2.jar"),
            mapOf(
                "com/vendor/tool/ToolPlugin.class" to pluginClass("com/vendor/tool/ToolPlugin", "com/vendor/text/Messages"),
                "META-INF/gradle-plugins/com.vendor.tool.properties" to "implementation-class=com.vendor.tool.ToolPlugin\n".toByteArray()
            )
        )
        SyntheticJars.write(File(sdk, "vendor/tools/vendor-text-1.0.jar"), mapOf("com/vendor/text/Messages.class" to helperClass("com/vendor/text/Messages")))
        SyntheticJars.write(File(sdk, "vendor/client/api-1.0.jar"), mapOf("com/vendor/Api.class" to SyntheticJars.clazz("com/vendor/Api")))
        File(sdk, "vendor.properties").writeText("vendor.version=2026.1.0\nvendor.category.client=client\nvendor.category.tools=tools\n")
        File(sdk, "settings.gradle.kts").writeText("plugins { id(\"lv.brain.vendor-distributor\") }\nrootProject.name = \"vendor-sdk\"\n")
        File(sdk, "build.gradle.kts").writeText("")
        GradleEnv.build(sdk, "packageVendorDistribution")
        val zip = zipEntries(File(sdk, "build/distributions/vendor-sdk-2026.1.0.zip"))
        assertTrue("com.vendor.tool" in String(zip.getValue("ivy/vendor-plugins.json")), zip.keys.toString())

        val app = File(tmp, "app").apply { mkdirs() }
        File(app, "vendor.properties").writeText("vendor.version=2026.1.0\nvendor.localZip=../sdk/build/distributions/vendor-sdk-2026.1.0.zip\n")
        File(app, "settings.gradle.kts").writeText("plugins { id(\"lv.brain.vendor-consumer\") }\nrootProject.name = \"app\"\n")
        File(app, "build.gradle.kts").writeText(
            """
            plugins {
                java
                id("com.vendor.tool")
            }
            dependencies { implementation(vendor.bundle("tools")); implementation(vendor.bundle("client")) }
            tasks.register("printClasspaths") {
                val runtimeFiles = configurations.runtimeClasspath.get().incoming.files
                doLast { println("RUNTIME=" + runtimeFiles.files.map { it.name }.sorted()) }
            }
            """.trimIndent() + "\n"
        )

        val output = GradleEnv.build(app, "printClasspaths").output

        assertTrue("VENDOR TOOL APPLIED" in output, output)
        assertEquals(listOf("api-1.0.jar", "vendor-text-1.0.jar"), classpath(output, "RUNTIME"), output)
    }
}
