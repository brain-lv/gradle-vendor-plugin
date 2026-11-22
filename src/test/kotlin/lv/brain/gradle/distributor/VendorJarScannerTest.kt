/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 *
 * Copyright (c) 2026 Edgars Grinvalds <edgars.grinvalds@brain.lv>
 */

package lv.brain.gradle.distributor

import groovy.json.JsonSlurper
import org.gradle.testfixtures.ProjectBuilder
import lv.brain.gradle.support.SyntheticJars
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.objectweb.asm.ClassWriter
import org.objectweb.asm.Opcodes
import java.io.File
import java.util.jar.Attributes
import java.util.jar.JarEntry
import java.util.jar.JarOutputStream
import java.util.jar.Manifest

/**
 * Jar identity detection. The jars of a multi-module library are the motivating case: they carry no Maven metadata,
 * every module has the same `Implementation-Title: org.example.searchkit`, and `Implementation-Version` holds
 * `4.2.1 <git hash> - <release manager> - <build date>`.
 */
class VendorJarScannerTest {

    @TempDir
    lateinit var tempDir: File

    private val searchkitVersionLine = "4.2.1 3f6c1d9e7a24b85c0e1f4a7d2b9c6e83a5d10f47 - builder - 2020-03-02 10:15:42"

    private fun searchkitManifest(module: String) = mapOf(
        "Extension-Name" to "org.example.searchkit",
        "Specification-Title" to "SearchKit Library: $module",
        "Specification-Version" to "4.2.1",
        "Specification-Vendor" to "Example Software Foundation",
        "Implementation-Title" to "org.example.searchkit",
        "Implementation-Version" to searchkitVersionLine,
        "Implementation-Vendor" to "Example Software Foundation"
    )

    private fun identify(
        jar: File,
        category: String = "search",
        defaultGroup: String = "lv.brain.vendor",
        groupRules: List<lv.brain.gradle.core.GroupRule> = emptyList()
    ): JarCoordinates = VendorJarScanner.resolveCoordinates(
        jar, VendorJarScanner.readFacts(jar), category, defaultGroup, "9.9.9", groupRules
    )

    @Test
    fun `searchkit analysis is detected as org example searchkit with a clean version`() {
        val jar = jar("searchkit-analysis-4.2.1.jar", searchkitManifest("analysis"))

        val coordinates = identify(jar)

        assertEquals("org.example.searchkit", coordinates.group)
        assertEquals("searchkit-analysis", coordinates.name)
        assertEquals("4.2.1", coordinates.version)
    }

    @Test
    fun `searchkit modules sharing one manifest title still get one artifact each`() {
        val names = listOf("core", "analysis", "query", "highlight")
        val detected = names.map { identify(jar("searchkit-$it-4.2.1.jar", searchkitManifest(it))) }

        assertEquals(names.map { "searchkit-$it" }, detected.map { it.name })
        assertEquals(setOf("org.example.searchkit"), detected.map { it.group }.toSet())
        assertEquals(setOf("4.2.1"), detected.map { it.version }.toSet())
    }

    @Test
    fun `embedded maven metadata wins over the manifest`() {
        val jar = jar(
            "searchkit-core-4.2.1.jar", searchkitManifest("core"),
            mavenCoordinates = Triple("org.example.searchkit", "searchkit-core", "4.2.1")
        )

        assertEquals(JarCoordinates("org.example.searchkit", "searchkit-core", "4.2.1"), identify(jar))
    }

    @Test
    fun `osgi symbolic name stays the artifact and directives are dropped`() {
        val jar = jar(
            "whatever.jar",
            mapOf(
                "Bundle-SymbolicName" to "org.example.engine; singleton:=true",
                "Bundle-Version" to "3.20.0.v20201027-1526",
                "Implementation-Title" to "org.example"
            )
        )

        assertEquals(JarCoordinates("lv.brain.vendor", "org.example.engine", "3.20.0.v20201027-1526"), identify(jar))
    }

    @Test
    fun `a plain implementation title is the artifact and vendor id is the group`() {
        val jar = jar(
            "ignored-1.0.jar",
            mapOf(
                "Implementation-Title" to "Fancy Lib",
                "Implementation-Version" to "2.4.1",
                "Implementation-Vendor-Id" to "com.fancy"
            )
        )

        assertEquals(JarCoordinates("com.fancy", "Fancy-Lib", "2.4.1"), identify(jar))
    }

    @Test
    fun `a placeholder or non group vendor id is ignored and the default group is used`() {
        for (vendorId in listOf("undefined", "Undefined", "unknown", "null", "\${vendor.id}", "BRAIN-LV", "  ")) {
            val jar = jar(
                "lv.brain.editor-5.6.7.jar",
                mapOf(
                    "Bundle-SymbolicName" to "lv.brain.editor",
                    "Bundle-Version" to "5.6.7",
                    "Implementation-Vendor-Id" to vendorId
                )
            )

            assertEquals(JarCoordinates("lv.brain.vendor", "lv.brain.editor", "5.6.7"), identify(jar), "vendor id '$vendorId'")
        }
    }

    @Test
    fun `usableGroup keeps real group ids`() {
        assertEquals("org.example.searchkit", VendorJarScanner.usableGroup(" org.example.searchkit "))
        assertEquals("com.fancy", VendorJarScanner.usableGroup("com.fancy"))
        assertEquals("commons-io", VendorJarScanner.usableGroup("commons-io"))
        assertEquals(null, VendorJarScanner.usableGroup("BRAIN-LV"))
        assertEquals(null, VendorJarScanner.usableGroup("Acme"))
        assertEquals(null, VendorJarScanner.usableGroup("undefined"))
        assertEquals(null, VendorJarScanner.usableGroup(null))
    }

    @Test
    fun `a manifest without a usable version falls through to group rules and then the file name`() {
        val noVersion = jar("thing-3.1.jar", mapOf("Implementation-Title" to "thing"))
        assertEquals(JarCoordinates("lv.brain.vendor.search", "thing", "3.1"), identify(noVersion))

        val rule = lv.brain.gradle.core.GroupRule(Regex("^(thing)-(\\d+\\.\\d+)\\.jar$"), "com.rule")
        assertEquals(JarCoordinates("com.rule", "thing", "3.1"), identify(noVersion, groupRules = listOf(rule)))
    }

    @Test
    fun `cleanVersion keeps the first token and rejects garbage`() {
        assertEquals("4.2.1", VendorJarScanner.cleanVersion(searchkitVersionLine))
        assertEquals("1.2.3-SNAPSHOT", VendorJarScanner.cleanVersion("  1.2.3-SNAPSHOT  "))
        assertEquals("31.1-jre", VendorJarScanner.cleanVersion("31.1-jre"))
        assertNull(VendorJarScanner.cleanVersion(""))
        assertNull(VendorJarScanner.cleanVersion(null))
        assertNull(VendorJarScanner.cleanVersion("(unknown)"))
    }

    @Test
    fun `sameContent tells identical copies from different files`() {
        val a = jar("a/searchkit-core-4.2.1.jar", searchkitManifest("core"))
        val copy = File(tempDir, "b/searchkit-core-4.2.1.jar").also { it.parentFile.mkdirs(); a.copyTo(it) }
        val other = jar("c/searchkit-core-4.2.1.jar", searchkitManifest("core") + ("X-Patched" to "yes"))

        assertTrue(VendorJarScanner.sameContent(a, copy))
        assertFalse(VendorJarScanner.sameContent(a, other))
    }

    @Test
    fun `resolve task builds one module per searchkit jar and wires the dependencies`() {
        val vendorDir = File(tempDir, "vendor").apply { mkdirs() }
        File(vendorDir, "lib").mkdirs()
        jar(
            "vendor/lib/searchkit-core-4.2.1.jar", searchkitManifest("core"),
            classes = mapOf("org/example/searchkit/util/Attribute" to emptyClass("org/example/searchkit/util/Attribute"))
        )
        jar(
            "vendor/lib/searchkit-analysis-4.2.1.jar", searchkitManifest("analysis"),
            classes = mapOf(
                "org/example/searchkit/analysis/TokenFilter" to
                    emptyClass("org/example/searchkit/analysis/TokenFilter", "org/example/searchkit/util/Attribute")
            )
        )
        jar(
            "vendor/lib/searchkit-query-4.2.1.jar", searchkitManifest("query"),
            classes = mapOf(
                "org/example/searchkit/query/QueryBase" to
                    emptyClass("org/example/searchkit/query/QueryBase", "org/example/searchkit/analysis/TokenFilter")
            )
        )

        val project = ProjectBuilder.builder().withProjectDir(tempDir).build()
        val task = project.tasks.register("resolveDeps", ResolveModuleDependenciesTask::class.java) {
            this.vendorDir.set(vendorDir)
            this.dependencyGraphFile.set(File(tempDir, "build/module-dependencies.json"))
        }.get()
        task.resolveDependencies()

        @Suppress("UNCHECKED_CAST")
        val json = JsonSlurper().parse(task.dependencyGraphFile.get().asFile) as Map<String, Any?>
        @Suppress("UNCHECKED_CAST")
        val modules = (json["modules"] as List<Map<String, Any?>>).associateBy { it["artifactId"] as String }

        assertEquals(setOf("searchkit-core", "searchkit-analysis", "searchkit-query"), modules.keys)
        for ((_, module) in modules) {
            assertEquals("org.example.searchkit", module["groupId"])
            assertEquals("4.2.1", module["version"])
        }

        fun deps(artifact: String): List<Any?> {
            @Suppress("UNCHECKED_CAST")
            return (modules.getValue(artifact)["dependsOn"] as List<Map<String, Any?>>).map { it["artifactId"] }
        }
        assertEquals(emptyList<Any?>(), deps("searchkit-core"))
        assertEquals(listOf("searchkit-core"), deps("searchkit-analysis"))
        assertEquals(listOf("searchkit-analysis"), deps("searchkit-query"))
    }

    // ---- helpers -------------------------------------------------------------------------------------------

    private fun jar(
        relativePath: String,
        manifest: Map<String, String>,
        classes: Map<String, ByteArray> = emptyMap(),
        mavenCoordinates: Triple<String, String, String>? = null
    ): File {
        val target = File(tempDir, relativePath).also { it.parentFile.mkdirs() }
        val mf = Manifest().apply {
            mainAttributes[Attributes.Name.MANIFEST_VERSION] = "1.0"
            manifest.forEach { (key, value) -> mainAttributes.putValue(key, value) }
        }
        JarOutputStream(target.outputStream(), mf).use { jos ->
            for ((internalName, bytes) in classes) {
                jos.putNextEntry(JarEntry("$internalName.class"))
                jos.write(bytes)
                jos.closeEntry()
            }
            if (mavenCoordinates != null) {
                val (g, a, v) = mavenCoordinates
                jos.putNextEntry(JarEntry("META-INF/maven/$g/$a/pom.properties"))
                jos.write("groupId=$g\nartifactId=$a\nversion=$v\n".toByteArray())
                jos.closeEntry()
            }
        }
        return target
    }

    private fun emptyClass(internalName: String, superName: String = "java/lang/Object"): ByteArray {
        val cw = ClassWriter(0)
        cw.visit(Opcodes.V17, Opcodes.ACC_PUBLIC, internalName, null, superName, null)
        cw.visitEnd()
        return cw.toByteArray()
    }

    @Test
    fun `a rule names its origin and wins over a manifest that names no group`() {
        val jar = File(tempDir, "spring-core-6.1.3.jar")
        SyntheticJars.write(
            jar,
            mapOf(
                "META-INF/MANIFEST.MF" to "Manifest-Version: 1.0\r\nImplementation-Title: spring-core\r\nImplementation-Version: 6.1.3\r\n\r\n".toByteArray(),
                "org/springframework/core/X.class" to SyntheticJars.clazz("org/springframework/core/X")
            )
        )
        val rule = lv.brain.gradle.core.GroupRule.parse("vendor.rule.1", "^(spring-[a-z-]+)-(\\d.*)\\.jar$ -> org.springframework oss")

        assertEquals("open-source", rule.origin)
        assertEquals("vendor-public", lv.brain.gradle.core.GroupRule.parse("vendor.rule.4", "^x$ -> g vendor-public").origin)
        assertEquals(JarCoordinates("org.springframework", "spring-core", "6.1.3"), identify(jar, groupRules = listOf(rule)))
        assertEquals(JarCoordinates("lv.brain.vendor", "spring-core", "6.1.3"), identify(jar))
        val noVersion = lv.brain.gradle.core.GroupRule.parse("vendor.rule.2", "^(acme-[a-z]+)(?:-(\\d.*))?\\.jar$ -> com.acme vendor")
        val acme = File(tempDir, "acme-core.jar").also { SyntheticJars.write(it, mapOf("com/acme/A.class" to SyntheticJars.clazz("com/acme/A"))) }
        assertEquals(JarCoordinates("com.acme", "acme-core", "9.9.9"), identify(acme, groupRules = listOf(noVersion)))
        org.junit.jupiter.api.assertThrows<IllegalArgumentException> { lv.brain.gradle.core.GroupRule.parse("vendor.rule.3", "^x$ -> g maybe") }
    }
}

