/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 *
 * Copyright (c) 2026 brain-lv (https://github.com/brain-lv)
 */

package lv.brain.gradle.consumer

import org.gradle.testkit.runner.GradleRunner
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.objectweb.asm.ClassWriter
import org.objectweb.asm.Opcodes
import java.io.File
import java.util.jar.JarEntry
import java.util.jar.JarOutputStream

// Offline smoke test for the consumer plugin: a vendor SDK is built by the distributor next to the consumer project
// (<tmp>/sdk and <tmp>/app) and the consumer is pointed at it through vendor.local.properties, once with a local
// folder (vendor.localDir) and once with the packaged zip (vendor.localZip). Nothing is downloaded.
class VendorConsumerLocalSmokeTest {

    @TempDir
    lateinit var tmp: File

    private val sdkDir get() = File(tmp, "sdk")
    private val appDir get() = File(tmp, "app")

    @BeforeEach
    fun buildSdkAndApp() {
        // --- distributor side: two categories, server depends on client (server.Impl extends client.Api) ---
        writeJar(File(sdkDir, "vendor/client/lib/demo-client-1.0.0.jar"), "com/demo/client/Api" to clazz("com/demo/client/Api"))
        writeJar(
            File(sdkDir, "vendor/server/lib/demo-server-1.0.0.jar"),
            "com/demo/server/Impl" to clazz("com/demo/server/Impl", superName = "com/demo/client/Api")
        )
        appDir.mkdirs()
        File(sdkDir, "vendor.properties").writeText(
            """
            vendor.version=2026.1.0
            vendor.slimJars=true
            vendor.category.client=client/lib/**
            vendor.category.server=server/lib/**
            """.trimIndent()
        )
        File(sdkDir, "build.gradle.kts").writeText("")
        File(sdkDir, "settings.gradle.kts").writeText(
            "plugins { id(\"lv.brain.vendor-distributor\") }\nrootProject.name = \"vendor-sdk\"\n"
        )

        // --- consumer side: the committed vendor.properties only has what a real project would commit ---
        File(appDir, "vendor.properties").writeText(
            """
            vendor.version=2026.1.0
            vendor.url=https://artifacts.invalid/vendor-sdk/vendor-sdk-{version}.zip
            """.trimIndent()
        )
        File(appDir, "settings.gradle").writeText(
            "plugins { id 'lv.brain.vendor-consumer' }\nrootProject.name = 'app'\n"
        )
        File(appDir, "build.gradle").writeText(
            """
            dependencies {
                implementation vendor.bundle('server')
            }
            tasks.register('printClasspaths') {
                doLast {
                    println 'COMPILE=' + configurations.compileClasspath.files*.name.sort()
                    println 'RUNTIME=' + configurations.runtimeClasspath.files*.name.sort()
                }
            }
            """.trimIndent()
        )
        File(appDir, "src/main/java/app/App.java").apply { parentFile.mkdirs() }.writeText(
            "package app;\npublic class App { com.demo.server.Impl impl; com.demo.client.Api api; }\n"
        )
    }

    @Test
    fun `consumer resolves the bundle from a local vendor folder`() {
        gradle(sdkDir, "generateVendorIvyRepo")
        File(appDir, "vendor.local.properties").writeText("vendor.localDir=../sdk/vendor\n")

        assertConsumerWorks()
    }

    @Test
    fun `consumer resolves the bundle from a local vendor zip`() {
        gradle(sdkDir, "packageVendorDistribution")
        File(appDir, "vendor.local.properties").writeText(
            "vendor.localZip=../sdk/build/distributions/vendor-sdk-2026.1.0.zip\nvendor.cacheMode=project\n"
        )

        assertConsumerWorks()
        assertTrue(File(appDir, ".vendor/2026.1.0/ivy").isDirectory, "zip should be unpacked below .vendor/2026.1.0")
    }

    @Test
    fun `root allprojects block can declare vendor dependencies for subprojects`() {
        gradle(sdkDir, "generateVendorIvyRepo")
        File(appDir, "vendor.local.properties").writeText("vendor.localDir=../sdk/vendor\n")
        File(appDir, "settings.gradle").appendText("include 'server'\n")
        // Subprojects have not been evaluated yet when the root script runs this block, so the plugin must have
        // prepared them (java plugin, vendor helper, repository) before: otherwise "Could not find method implementation()".
        File(appDir, "build.gradle").writeText(
            """
            subprojects {
                dependencies {
                    implementation vendor.bundle('server')
                }
                tasks.register('printClasspaths') {
                    doLast { println 'RUNTIME=' + configurations.runtimeClasspath.files*.name.sort() }
                }
            }
            """.trimIndent()
        )
        File(appDir, "server/src/main/java/app/Server.java").apply { parentFile.mkdirs() }.writeText(
            "package app;\npublic class Server { com.demo.server.Impl impl; }\n"
        )

        val output = gradle(appDir, ":server:compileJava", ":server:printClasspaths")

        val runtime = output.lineSequence().first { it.startsWith("RUNTIME=") }
        assertTrue("demo-server-1.0.0.jar" in runtime && "demo-client-1.0.0.jar" in runtime, output)
    }

    @Test
    fun `local files of a project in a subfolder are excluded through the parent repository`() {
        gradle(sdkDir, "generateVendorIvyRepo")
        File(tmp, ".git").mkdirs()
        File(appDir, "vendor.local.properties").writeText("vendor.localDir=../sdk/vendor\n")

        gradle(appDir, "help")

        val lines = File(tmp, ".git/info/exclude").readLines()
        assertTrue("/app/vendor.local.properties" in lines, lines.toString())
        assertTrue("/app/.vendor/" in lines, lines.toString())
        assertTrue(!File(appDir, ".gitignore").exists(), "no .gitignore may be written")
        assertTrue(!File(tmp, ".gitignore").exists(), "no .gitignore may be written")
    }

    @Test
    fun `vendor gitExclude false leaves git alone`() {
        gradle(sdkDir, "generateVendorIvyRepo")
        File(tmp, ".git").mkdirs()
        File(appDir, "vendor.local.properties").writeText("vendor.localDir=../sdk/vendor\nvendor.gitExclude=false\n")

        gradle(appDir, "help")

        assertTrue(!File(tmp, ".git/info/exclude").exists(), "the exclude file must not be written")
        assertTrue(!File(appDir, ".gitignore").exists(), "no .gitignore may be written")
    }

    private fun assertConsumerWorks() {
        val output = gradle(appDir, "compileJava", "printClasspaths")

        // compiles against the slim ABI jars, and the transitive client jar comes with the server bundle
        val compile = output.lineSequence().first { it.startsWith("COMPILE=") }
        assertTrue("demo-server-1.0.0-slim.jar" in compile && "demo-client-1.0.0-slim.jar" in compile, output)
        // runtime and tests get the original jars
        val runtime = output.lineSequence().first { it.startsWith("RUNTIME=") }
        assertTrue("demo-server-1.0.0.jar" in runtime && "demo-client-1.0.0.jar" in runtime, output)
        assertTrue("-slim" !in runtime, output)
    }

    private fun gradle(dir: File, vararg args: String): String =
        GradleRunner.create()
            .withProjectDir(dir)
            .withPluginClasspath()
            .withArguments(*args, "--stacktrace")
            .build()
            .output

    private fun clazz(internalName: String, superName: String = "java/lang/Object"): ByteArray {
        val cw = ClassWriter(0)
        cw.visit(Opcodes.V17, Opcodes.ACC_PUBLIC, internalName, null, superName, null)
        cw.visitEnd()
        return cw.toByteArray()
    }

    private fun writeJar(target: File, vararg classes: Pair<String, ByteArray>) {
        target.parentFile.mkdirs()
        JarOutputStream(target.outputStream()).use { jos ->
            for ((internalName, bytes) in classes) {
                jos.putNextEntry(JarEntry("$internalName.class"))
                jos.write(bytes)
                jos.closeEntry()
            }
        }
    }
}
