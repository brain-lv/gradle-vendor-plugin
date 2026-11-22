/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 *
 * Copyright (c) 2026 Edgars Grinvalds <edgars.grinvalds@brain.lv>
 */

package lv.brain.gradle.distributor

import groovy.json.JsonSlurper
import lv.brain.gradle.core.CategoryRule
import org.gradle.testfixtures.ProjectBuilder
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.objectweb.asm.ClassWriter
import org.objectweb.asm.Opcodes
import java.io.File
import java.util.jar.JarEntry
import java.util.jar.JarOutputStream
import java.util.zip.ZipFile

class ResolveModuleDependenciesTaskTest {

    @TempDir
    lateinit var tempDir: File

    @Test
    fun `resolves maven pom inside folder and drops external deps while falling back to bytecode for plain jars`() {
        val vendorDir = File(tempDir, "vendor").apply { mkdirs() }

        // 1. Plain JAR (no Maven metadata): defines com/vendor/core/CoreApi
        createJar(
            File(vendorDir, "vendor-core-1.0.0.jar"),
            classes = mapOf("com/vendor/core/CoreApi" to emptyClass("com/vendor/core/CoreApi"))
        )

        // 2. Maven JAR: pom.xml lists vendor-core (present) AND example-log-api (NOT in vendorDir)
        createJar(
            File(vendorDir, "vendor-service-1.0.0.jar"),
            classes = mapOf("com/vendor/service/ServiceImpl" to emptyClass("com/vendor/service/ServiceImpl")),
            mavenGroupId = "com.vendor",
            mavenArtifactId = "vendor-service",
            mavenVersion = "1.0.0",
            pomXml = """
                <project>
                  <modelVersion>4.0.0</modelVersion>
                  <groupId>com.vendor</groupId>
                  <artifactId>vendor-service</artifactId>
                  <version>1.0.0</version>
                  <dependencies>
                    <dependency>
                      <groupId>lv.brain.vendor</groupId>
                      <artifactId>vendor-core</artifactId>
                      <version>1.0.0</version>
                    </dependency>
                    <dependency>
                      <groupId>org.example.log</groupId>
                      <artifactId>example-log-api</artifactId>
                      <version>2.0.12</version>
                    </dependency>
                  </dependencies>
                </project>
            """.trimIndent()
        )

        // 3. Plain JAR (no Maven metadata): extends com/vendor/service/ServiceImpl -> detected via bytecode
        createJar(
            File(vendorDir, "vendor-client-1.0.0.jar"),
            classes = mapOf(
                "com/vendor/client/ClientApp" to emptyClass(
                    internalName = "com/vendor/client/ClientApp",
                    superName = "com/vendor/service/ServiceImpl"
                )
            )
        )

        val project = ProjectBuilder.builder().withProjectDir(tempDir).build()
        val resolveTask = project.tasks.register("resolveDeps", ResolveModuleDependenciesTask::class.java) {
            this.vendorDir.set(vendorDir)
            this.dependencyGraphFile.set(File(tempDir, "build/module-dependencies.json"))
        }.get()

        resolveTask.resolveDependencies()

        val json = readJson(resolveTask.dependencyGraphFile.get().asFile)
        @Suppress("UNCHECKED_CAST")
        val topoOrder = json["topologicalOrder"] as List<String>
        assertEquals(listOf("vendor-core", "vendor-service", "vendor-client"), topoOrder)

        val modules = modulesById(json)

        val serviceDeps = dependsOn(modules, "vendor-service")
        assertEquals(listOf("vendor-core"), serviceDeps)
        assertFalse(serviceDeps.contains("example-log-api"))

        assertEquals(listOf("vendor-service"), dependsOn(modules, "vendor-client"))

        // Generate the Ivy repository from the graph and package it
        val ivyDir = File(vendorDir, "ivy")
        val generateTask = project.tasks.register("generateIvy", GenerateVendorIvyRepoTask::class.java) {
            this.vendorDir.set(vendorDir)
            this.dependencyGraphFile.set(resolveTask.dependencyGraphFile)
            this.versionString.set("1.0.0")
            this.bundleGroup.set("lv.brain.vendor.bundle")
            this.ivyDir.set(ivyDir)
        }.get()
        generateTask.generate()

        val packageTask = project.tasks.register("packageIvy", PackageVendorZipTask::class.java) {
            this.vendorDir.set(vendorDir)
            this.ivyDir.set(ivyDir)
            this.dependencyGraphFile.set(resolveTask.dependencyGraphFile)
            this.outputZip.set(File(tempDir, "build/vendor-sdk.zip"))
        }.get()
        packageTask.packageZip()

        ZipFile(packageTask.outputZip.get().asFile).use { zip ->
            // ivy.xml chains the vendor-internal dependency and drops the external one
            val clientIvy = zip.getEntry("ivy/lv.brain.vendor.core/vendor-client-1.0.0/ivy-1.0.0.xml")
            assertNotNull(clientIvy, "ivy.xml must live below ivy/<group>/<module>-<version>")
            val ivyContent = zip.getInputStream(clientIvy).bufferedReader().readText()
            assertTrue(ivyContent.contains("""<dependency org="com.vendor" name="vendor-service" rev="1.0.0""""))
            assertFalse(ivyContent.contains("example-log-api"))

            // Module metadata points at the original jar by relative url; the jar is not duplicated below ivy/
            val coreModule = zip.getEntry("ivy/lv.brain.vendor.core/vendor-core-1.0.0/vendor-core-1.0.0.module")
            assertNotNull(coreModule)
            assertTrue(
                zip.getInputStream(coreModule).bufferedReader().readText()
                    .contains("\"url\": \"../../../vendor-core-1.0.0.jar\"")
            )
            assertNull(zip.getEntry("ivy/lv.brain.vendor.core/vendor-core-1.0.0/vendor-core-1.0.0.jar"))
            assertNotNull(zip.getEntry("vendor-core-1.0.0.jar"))
            assertNotNull(zip.getEntry("module-dependencies.json"))

            // Gradle Module Metadata chains the dependency as well
            val clientModule = zip.getEntry("ivy/lv.brain.vendor.core/vendor-client-1.0.0/vendor-client-1.0.0.module")
            assertTrue(zip.getInputStream(clientModule).bufferedReader().readText().contains("\"module\": \"vendor-service\""))

            // Everything lands in the default category when no include is declared
            assertNotNull(zip.getEntry("ivy/lv.brain.vendor.bundle/core-1.0.0/core-1.0.0.module"))
        }
    }

    @Test
    fun `jars that match no category include are left out and a module shared by two categories joins both bundles`() {
        val vendorDir = File(tempDir, "vendor").apply { mkdirs() }
        // same module (embedded coordinates) in two folders, as it happens with example-log & co in client and server
        createSharedJar(File(vendorDir, "client/lib/shared-1.0.0.jar"))
        createSharedJar(File(vendorDir, "server/lib/shared-1.0.0.jar"))
        createPlainJar(File(vendorDir, "server/lib/only-server-2.0.0.jar"), "com/server/OnlyServer")
        createPlainJar(File(vendorDir, "tools/lib/leftover-1.0.0.jar"), "com/tools/Leftover")

        val project = ProjectBuilder.builder().withProjectDir(tempDir).build()
        val resolveTask = project.tasks.register("resolveDeps", ResolveModuleDependenciesTask::class.java) {
            this.vendorDir.set(vendorDir)
            this.dependencyGraphFile.set(File(tempDir, "build/module-dependencies.json"))
            // different pattern styles on purpose: any-depth, plain directory, vendor directory prefix
            this.categoryRules.set(
                listOf(
                    CategoryRule("client", listOf("**/client/**")),
                    CategoryRule("server", listOf("vendor/server"))
                )
            )
            this.skipUnclassified.set(true)
        }.get()

        resolveTask.resolveDependencies()

        val json = readJson(resolveTask.dependencyGraphFile.get().asFile)
        assertEquals(1, json["skippedJarCount"])
        assertEquals(2, json["moduleCount"])

        val modules = modulesById(json)
        assertEquals(setOf("shared", "only-server"), modules.keys)
        assertEquals(listOf("client", "server"), modules.getValue("shared")["categories"])
        assertEquals(listOf("server"), modules.getValue("only-server")["categories"])

        val ivyDir = File(vendorDir, "ivy")
        project.tasks.register("generateIvy", GenerateVendorIvyRepoTask::class.java) {
            this.vendorDir.set(vendorDir)
            this.dependencyGraphFile.set(resolveTask.dependencyGraphFile)
            this.versionString.set("1.0.0")
            this.bundleGroup.set("lv.brain.vendor.bundle")
            this.ivyDir.set(ivyDir)
        }.get().generate()

        fun bundle(name: String) = File(ivyDir, "lv.brain.vendor.bundle/$name-1.0.0/$name-1.0.0.module").readText()
        assertTrue(bundle("client").contains("\"module\": \"shared\""))
        assertFalse(bundle("client").contains("only-server"))
        assertTrue(bundle("server").contains("\"module\": \"shared\""), "shared jar must not vanish from the second bundle")
        assertTrue(bundle("server").contains("\"module\": \"only-server\""))
        assertFalse(File(ivyDir, "lv.brain.vendor.bundle/core-1.0.0").exists(), "no bundle for the left out jars")
    }

    @Test
    fun `unclassified jars use the fallback category when it is requested explicitly`() {
        val vendorDir = File(tempDir, "vendor").apply { mkdirs() }
        createPlainJar(File(vendorDir, "client/lib/a-1.0.0.jar"), "com/a/A")
        createPlainJar(File(vendorDir, "tools/lib/b-1.0.0.jar"), "com/b/B")

        val project = ProjectBuilder.builder().withProjectDir(tempDir).build()
        val resolveTask = project.tasks.register("resolveDeps", ResolveModuleDependenciesTask::class.java) {
            this.vendorDir.set(vendorDir)
            this.dependencyGraphFile.set(File(tempDir, "build/module-dependencies.json"))
            this.categoryRules.set(listOf(CategoryRule("client", listOf("client/lib/**"))))
            this.fallbackCategory.set("extras")
            this.skipUnclassified.set(false)
        }.get()

        resolveTask.resolveDependencies()

        val modules = modulesById(readJson(resolveTask.dependencyGraphFile.get().asFile))
        assertEquals(listOf("client"), modules.getValue("a")["categories"])
        assertEquals(listOf("extras"), modules.getValue("b")["categories"])
    }

    private fun readJson(file: File): Map<String, Any?> {
        @Suppress("UNCHECKED_CAST")
        return JsonSlurper().parse(file) as Map<String, Any?>
    }

    private fun modulesById(json: Map<String, Any?>): Map<String, Map<String, Any?>> {
        @Suppress("UNCHECKED_CAST")
        return (json["modules"] as List<Map<String, Any?>>).associateBy { it["moduleId"] as String }
    }

    private fun dependsOn(modules: Map<String, Map<String, Any?>>, moduleId: String): List<Any?> {
        @Suppress("UNCHECKED_CAST")
        return (modules[moduleId]?.get("dependsOn") as List<Map<String, Any?>>).map { it["artifactId"] }
    }

    private fun createSharedJar(target: File) {
        target.parentFile.mkdirs()
        createJar(
            target,
            classes = mapOf("com/shared/Shared" to emptyClass("com/shared/Shared")),
            mavenGroupId = "com.shared",
            mavenArtifactId = "shared",
            mavenVersion = "1.0.0"
        )
    }

    private fun createPlainJar(target: File, internalClassName: String) {
        target.parentFile.mkdirs()
        createJar(target, classes = mapOf(internalClassName to emptyClass(internalClassName)))
    }

    private fun emptyClass(internalName: String, superName: String = "java/lang/Object"): ByteArray {
        val cw = ClassWriter(0)
        cw.visit(Opcodes.V17, Opcodes.ACC_PUBLIC, internalName, null, superName, null)
        cw.visitEnd()
        return cw.toByteArray()
    }

    private fun createJar(
        target: File,
        classes: Map<String, ByteArray>,
        mavenGroupId: String? = null,
        mavenArtifactId: String? = null,
        mavenVersion: String? = null,
        pomXml: String? = null
    ) {
        JarOutputStream(target.outputStream()).use { jos ->
            for ((internalName, bytes) in classes) {
                jos.putNextEntry(JarEntry("$internalName.class"))
                jos.write(bytes)
                jos.closeEntry()
            }
            if (mavenGroupId != null && mavenArtifactId != null && mavenVersion != null) {
                val base = "META-INF/maven/$mavenGroupId/$mavenArtifactId"
                jos.putNextEntry(JarEntry("$base/pom.properties"))
                jos.write("groupId=$mavenGroupId\nartifactId=$mavenArtifactId\nversion=$mavenVersion\n".toByteArray())
                jos.closeEntry()
                if (pomXml != null) {
                    jos.putNextEntry(JarEntry("$base/pom.xml"))
                    jos.write(pomXml.toByteArray())
                    jos.closeEntry()
                }
            }
        }
    }
}
