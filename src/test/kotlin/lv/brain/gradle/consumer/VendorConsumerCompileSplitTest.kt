/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 *
 * Copyright (c) 2026 Edgars Grinvalds <edgars.grinvalds@brain.lv>
 */

package lv.brain.gradle.consumer

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

// End to end, offline: a vendor SDK is built with vendor.compilePackages, then a project consumes it.
//
//   com.demo.client.Api   (compile package)   public API returns org.leak.Leak            -> leak jar is promoted
//   org.lite.Util         (commons-lite 3.8)  not part of any API                         -> runtime only
//   org.leak.Leak         (leak 1.0.0)
class VendorConsumerCompileSplitTest {

    @TempDir
    lateinit var tmp: File

    private val sdkDir get() = File(tmp, "sdk")
    private val appDir get() = File(tmp, "app")

    private fun clazz(internalName: String, leaks: String? = null): ByteArray {
        val cw = ClassWriter(ClassWriter.COMPUTE_MAXS)
        cw.visit(Opcodes.V17, Opcodes.ACC_PUBLIC, internalName, null, "java/lang/Object", null)
        if (leaks != null) {
            val mv = cw.visitMethod(Opcodes.ACC_PUBLIC, "leak", "()L$leaks;", null, null)
            mv.visitCode(); mv.visitInsn(Opcodes.ACONST_NULL); mv.visitInsn(Opcodes.ARETURN); mv.visitMaxs(0, 0); mv.visitEnd()
        }
        cw.visitEnd()
        return cw.toByteArray()
    }

    private fun writeJar(target: File, entries: Map<String, ByteArray>) {
        target.parentFile.mkdirs()
        JarOutputStream(target.outputStream()).use { jos ->
            for ((name, bytes) in entries) {
                jos.putNextEntry(JarEntry(name)); jos.write(bytes); jos.closeEntry()
            }
        }
    }

    /** A jar that identifies itself as org.lite:commons-lite:<version> through pom.properties. */
    private fun liteJar(target: File, version: String) = writeJar(
        target,
        mapOf(
            "org/lite/Util.class" to clazz("org/lite/Util"),
            "META-INF/maven/org.lite/commons-lite/pom.properties" to
                "groupId=org.lite\nartifactId=commons-lite\nversion=$version\n".toByteArray()
        )
    )

    private fun buildSdk(compilePackages: String?) {
        writeJar(File(sdkDir, "vendor/client/lib/demo-client-1.0.0.jar"), mapOf("com/demo/client/Api.class" to clazz("com/demo/client/Api", leaks = "org/leak/Leak")))
        writeJar(File(sdkDir, "vendor/client/lib/leak-1.0.0.jar"), mapOf("org/leak/Leak.class" to clazz("org/leak/Leak")))
        liteJar(File(sdkDir, "vendor/client/lib/commons-lite-3.8.jar"), "3.8")
        File(sdkDir, "vendor.properties").writeText(
            listOfNotNull(
                "vendor.version=2026.1.0",
                "vendor.slimJars=true",
                "vendor.category.client=client/lib/**",
                compilePackages?.let { "vendor.compilePackages=$it" }
            ).joinToString("\n")
        )
        File(sdkDir, "build.gradle.kts").writeText("")
        File(sdkDir, "settings.gradle.kts").writeText("plugins { id(\"lv.brain.vendor-distributor\") }\nrootProject.name = \"vendor-sdk\"\n")
        gradle(sdkDir, "generateVendorIvyRepo")
    }

    private fun setUpApp(
        extraDependencies: String = "",
        extraProperties: String = "",
        appSource: String = "package app;\npublic class App { com.demo.client.Api api; Object use() { return api.leak(); } }\n"
    ) {
        appDir.mkdirs()
        File(appDir, "vendor.properties").writeText("vendor.version=2026.1.0\nvendor.url=https://artifacts.invalid/vendor-sdk/vendor-sdk-{version}.zip\n")
        File(appDir, "vendor.local.properties").writeText("vendor.localDir=../sdk/vendor\n$extraProperties")
        File(appDir, "settings.gradle").writeText("plugins { id 'lv.brain.vendor-consumer' }\nrootProject.name = 'app'\n")
        File(appDir, "build.gradle").writeText(
            """
            repositories { maven { url = uri('local-repo') } }
            dependencies {
                implementation vendor.bundle('client')
                $extraDependencies
            }
            tasks.register('printClasspaths') {
                doLast {
                    println 'COMPILE=' + configurations.compileClasspath.files*.name.sort()
                    println 'RUNTIME=' + configurations.runtimeClasspath.files*.name.sort()
                }
            }
            """.trimIndent()
        )
        File(appDir, "src/main/java/app/App.java").apply { parentFile.mkdirs() }.writeText(appSource)
    }

    private fun localMavenLite(version: String) {
        val dir = File(appDir, "local-repo/org/lite/commons-lite/$version").apply { mkdirs() }
        File(dir, "commons-lite-$version.pom").writeText(
            "<project><modelVersion>4.0.0</modelVersion><groupId>org.lite</groupId><artifactId>commons-lite</artifactId><version>$version</version></project>"
        )
        liteJar(File(dir, "commons-lite-$version.jar"), version)
    }

    private fun line(output: String, prefix: String) = output.lineSequence().first { it.startsWith(prefix) }

    @Test
    fun `compile classpath is the compile packages plus what their api needs and runtime has everything`() {
        buildSdk(compilePackages = "com.demo")
        setUpApp()

        val output = gradle(appDir, "compileJava", "printClasspaths")

        val compile = line(output, "COMPILE=")
        assertTrue("demo-client-1.0.0-slim.jar" in compile, compile)
        assertTrue("leak-1.0.0-slim.jar" in compile, "the jar that the public api needs is promoted: $compile")
        assertTrue("commons-lite" !in compile, "runtime-only jars stay off the compile classpath: $compile")

        val runtime = line(output, "RUNTIME=")
        assertTrue("demo-client-1.0.0.jar" in runtime && "leak-1.0.0.jar" in runtime && "commons-lite-3.8.jar" in runtime, runtime)
        assertTrue("-slim" !in runtime, runtime)
    }

    @Test
    fun `code cannot compile against a runtime only vendor jar`() {
        buildSdk(compilePackages = "com.demo")
        setUpApp(appSource = "package app;\npublic class App { org.lite.Util util; }\n")

        val result = GradleRunner.create().withProjectDir(appDir).withPluginClasspath()
            .withArguments("compileJava", "--stacktrace").buildAndFail()

        assertTrue("package org.lite does not exist" in result.output, result.output)
    }

    @Test
    fun `a project dependency on another version of a vendor library fails before compiling`() {
        buildSdk(compilePackages = "com.demo")
        localMavenLite("3.12")
        setUpApp(extraDependencies = "implementation 'org.lite:commons-lite:3.12'")

        val result = GradleRunner.create().withProjectDir(appDir).withPluginClasspath()
            .withArguments("compileJava").buildAndFail()

        val message = result.output
        assertTrue("Vendor runtime jars clash" in message, message)
        assertTrue("org.lite:commons-lite" in message && "3.8" in message && "3.12" in message, message)
        assertTrue("project ':' asks for 3.12  [compile + runtime classpath]" in message, message)
        assertTrue(result.tasks.none { it.path == ":compileJava" && it.outcome.name == "SUCCESS" }, "compile must not run: ${result.tasks}")
    }

    @Test
    fun `the same version as the vendor jar is fine and the check is skipped while nothing changed`() {
        buildSdk(compilePackages = "com.demo")
        localMavenLite("3.8")
        setUpApp(
            extraDependencies = "implementation 'org.lite:commons-lite:3.8'",
            appSource = "package app;\npublic class App { org.lite.Util util; com.demo.client.Api api; }\n"
        )

        val first = gradle(appDir, "compileJava")
        assertTrue(":verifyVendorClasspath" in first, first)

        // every source set is checked before its own compile task
        assertTrue(":verifyTestVendorClasspath" in gradle(appDir, "compileTestJava"))

        val second = GradleRunner.create().withProjectDir(appDir).withPluginClasspath().withArguments("compileJava").build()
        assertEquals("UP_TO_DATE", second.task(":verifyVendorClasspath")?.outcome?.name, second.output)
    }

    @Test
    fun `clashCheck false and clashIgnore switch the check off`() {
        buildSdk(compilePackages = "com.demo")
        localMavenLite("3.12")
        setUpApp(extraDependencies = "implementation 'org.lite:commons-lite:3.12'", extraProperties = "vendor.clashCheck=false\n")
        gradle(appDir, "compileJava")

        File(appDir, "vendor.local.properties").writeText("vendor.localDir=../sdk/vendor\nvendor.clashIgnore=org.lite:*\n")
        gradle(appDir, "compileJava", "--rerun-tasks")
    }

    @Test
    fun `a distribution without runtime only jars is not checked and keeps every jar on the compile classpath`() {
        buildSdk(compilePackages = null)
        setUpApp()

        val output = gradle(appDir, "compileJava", "printClasspaths")

        assertTrue("Vendor runtime jars clash" !in output, output)
        val compile = line(output, "COMPILE=")
        assertTrue("commons-lite-3.8-slim.jar" in compile && "demo-client-1.0.0-slim.jar" in compile, compile)
    }

    private fun gradle(dir: File, vararg args: String): String =
        GradleRunner.create()
            .withProjectDir(dir)
            .withPluginClasspath()
            .withArguments(*args, "--stacktrace")
            .build()
            .output
}
