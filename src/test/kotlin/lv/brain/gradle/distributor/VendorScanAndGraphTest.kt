/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 *
 * Copyright (c) 2026 Edgars Grinvalds <edgars.grinvalds@brain.lv>
 */

package lv.brain.gradle.distributor

import groovy.json.JsonSlurper
import lv.brain.gradle.support.SyntheticJars
import org.gradle.testfixtures.ProjectBuilder
import org.gradle.api.GradleException
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

class VendorScanAndGraphTest {

    @TempDir
    lateinit var tempDir: File

    private val vendorDir get() = File(tempDir, "vendor")

    private fun jar(path: String, vararg entries: Pair<String, ByteArray>) = SyntheticJars.write(File(vendorDir, path), entries.toMap())

    private fun cls(name: String, publicReturns: String? = null, bodyUses: String? = null) =
        "$name.class" to SyntheticJars.clazz(name, publicReturns = publicReturns, bodyUses = bodyUses)

    private fun pom(group: String, artifact: String, dependencies: String = "") =
        "META-INF/maven/$group/$artifact/pom.xml" to
            "<project><groupId>$group</groupId><artifactId>$artifact</artifactId><version>1.0</version><dependencies>$dependencies</dependencies></project>".toByteArray()

    private fun resolve(
        compilePackages: List<String> = emptyList(),
        scanExcludes: List<String> = emptyList(),
        add: List<String> = emptyList(),
        remove: List<String> = emptyList(),
        pomCheck: Boolean = false
    ): Map<String, Any?> {
        val project = ProjectBuilder.builder().withProjectDir(tempDir).build()
        val task = project.tasks.register("resolveDeps", ResolveModuleDependenciesTask::class.java) {
            this.vendorDir.set(this@VendorScanAndGraphTest.vendorDir)
            this.dependencyGraphFile.set(File(tempDir, "build/module-dependencies.json"))
            this.compilePackages.set(compilePackages)
            this.scanExcludes.set(scanExcludes)
            this.dependencyAdd.set(add)
            this.dependencyRemove.set(remove)
            this.pomCheck.set(if (pomCheck) "warn" else "off")
        }.get()
        task.resolveDependencies()
        @Suppress("UNCHECKED_CAST")
        return JsonSlurper().parse(task.dependencyGraphFile.get().asFile) as Map<String, Any?>
    }

    @Suppress("UNCHECKED_CAST")
    private fun modules(json: Map<String, Any?>): Map<String, Map<String, Any?>> =
        (json["modules"] as List<Map<String, Any?>>).associateBy { it["moduleId"] as String }

    @Suppress("UNCHECKED_CAST")
    private fun deps(json: Map<String, Any?>, id: String, key: String = "dependsOn"): List<String> =
        (modules(json).getValue(id)[key] as List<Map<String, Any?>>).map { it["moduleId"] as String }

    @Test
    fun `hidden folders, ivy, a top level build folder and scan excludes are neither scanned nor task inputs`() {
        for (path in listOf(
            "lib/a-1.0.jar", ".gradle/caches/gradle-api-8.7.jar", "lib/.cache/c-1.0.jar", ".vendor/x-1.0.jar", "build/libs/b-1.0.jar",
            "ivy/g/m-1/m-1-slim.jar", "tools/legacy/old-1.0.jar", "sub/build/keep-1.0.jar", "lib/.hidden-1.0.jar"
        )) jar(path, cls("p/C"))

        val selection = VendorJarScanner.selectJars(vendorDir, emptyList(), null, "core", listOf("tools/legacy"))
        assertEquals(listOf("lib/a-1.0.jar", "sub/build/keep-1.0.jar"), selection.jars.map { it.relativePath })

        val project = ProjectBuilder.builder().withProjectDir(tempDir).build()
        val dir = project.objects.directoryProperty().apply { set(vendorDir) }
        val inputs = VendorJarScanner.jarTree(dir, project.provider { listOf("tools/legacy") }).files
            .map { it.relativeTo(vendorDir).invariantSeparatorsPath }.sorted()
        assertEquals(selection.jars.map { it.relativePath }, inputs)
    }

    @Test
    fun `jars that only the default category keeps are listed`() {
        jar("client/a-1.0.jar", cls("p/A"))
        jar("other/b-1.0.jar", cls("p/B"))

        val selection = VendorJarScanner.selectJars(vendorDir, listOf(lv.brain.gradle.core.CategoryRule("client", listOf("client"))), null, "misc")

        assertEquals(listOf("other/b-1.0.jar"), selection.defaulted)
    }

    @Test
    fun `a pom without dependencies means no dependencies, and several or broken poms count as none`() {
        jar("lib/target-1.0.jar", cls("t/T"))
        jar("lib/with-pom-1.0.jar", cls("w/W", bodyUses = "t/T"), pom("org.w", "with-pom"))
        jar(
            "lib/fat-1.0.jar", cls("f/F", bodyUses = "t/T"), pom("org.a", "a"), pom("org.b", "b"),
            SyntheticJars.pomProperties("org.a", "a", "1.0"), SyntheticJars.pomProperties("org.b", "b", "1.0")
        )
        jar("lib/broken-1.0.jar", cls("b/B", bodyUses = "t/T"), "META-INF/maven/org.x/broken/pom.xml" to "<project><broken".toByteArray())

        val json = resolve()
        val byId = modules(json)

        assertEquals("maven-pom", byId.getValue("with-pom")["resolutionSource"])
        assertEquals(emptyList<String>(), deps(json, "with-pom"))
        assertEquals("bytecode-scan", byId.getValue("fat")["resolutionSource"])
        assertEquals(listOf("target"), deps(json, "fat"))
        assertEquals("lv.brain.vendor.core", byId.getValue("fat")["groupId"])
        assertEquals("bytecode-scan", byId.getValue("broken")["resolutionSource"])
        assertEquals(listOf("target"), deps(json, "broken"))
    }

    @Test
    fun `the weakest edge of a cycle is removed, so a chain still covers a dependency of the dependent`() {
        // chain m1 -> m2 -> m3 -> a4 with two references per edge, and a single reference back from a4 to m2
        jar("a4-1.0.jar", cls("a4/A", bodyUses = "m2/A"), cls("a4/B"))
        jar("m1-1.0.jar", cls("m1/A", publicReturns = "m2/A", bodyUses = "m2/B"))
        jar("m2-1.0.jar", cls("m2/A", publicReturns = "m3/A", bodyUses = "m3/B"), cls("m2/B"))
        jar("m3-1.0.jar", cls("m3/A", publicReturns = "a4/A", bodyUses = "a4/B"), cls("m3/B"))
        jar("x-1.0.jar", cls("x/X", publicReturns = "m1/A", bodyUses = "a4/A"))

        val json = resolve()

        @Suppress("UNCHECKED_CAST")
        val removed = (json["brokenCycles"] as List<Map<String, Any?>>).map { it["removedEdge"] }
        assertEquals(listOf(mapOf("from" to "a4", "to" to "m2")), removed)
        assertEquals(listOf("m1"), deps(json, "x"))
        assertEquals(listOf("m3"), deps(json, "m2"))
        assertEquals(listOf("a4"), deps(json, "m3"))
    }

    @Test
    fun `compile dependencies reach through runtime only jars and are then reduced`() {
        // c1 needs r2 at runtime, r2 needs r3, r3 needs c4: only c1, c4 and x are in the compile package
        jar("c1-1.0.jar", cls("com/api/C1", bodyUses = "r2/R"))
        jar("r2-1.0.jar", cls("r2/R", bodyUses = "r3/R"))
        jar("r3-1.0.jar", cls("r3/R", bodyUses = "com/api/C4"))
        jar("c4-1.0.jar", cls("com/api/C4"))
        jar("x-1.0.jar", cls("com/api/X", bodyUses = "com/api/C1"), cls("com/api/Y", bodyUses = "com/api/C4"))

        val json = resolve(compilePackages = listOf("com.api"))

        assertEquals(listOf("c1"), deps(json, "x"))
        assertEquals(listOf("c1"), deps(json, "x", "apiDependsOn"))
        assertEquals(listOf("c4"), deps(json, "c1", "apiDependsOn"))
        assertEquals(listOf("r2"), deps(json, "c1"))
    }

    @Test
    fun `a jar uses its own copy of a shared class, and the shared classes are listed`() {
        jar("a-1.0.jar", cls("shared/S"), cls("a/A", bodyUses = "shared/S"))
        jar("b-1.0.jar", cls("shared/S"), cls("b/B", bodyUses = "shared/S"))
        jar("c-1.0.jar", cls("c/C", bodyUses = "shared/S"))

        val json = resolve()

        assertEquals(emptyList<String>(), deps(json, "b"))
        assertEquals(listOf("a"), deps(json, "c"))
        assertEquals(listOf(mapOf("jars" to listOf("a", "b"), "classCount" to 1, "examples" to listOf("shared.S"))), json["sharedClasses"])
    }

    @Test
    fun `the scan cache keeps one entry per jar content and drops entries of changed jars`() {
        jar("a-1.0.jar", cls("a/A", bodyUses = "b/B"))
        jar("b-1.0.jar", cls("b/B"))
        val cacheDir = File(tempDir, "build/vendor/scan-cache")

        val first = resolve()
        val entries = cacheDir.list().orEmpty().sorted()
        assertEquals(2, entries.size, "$entries")
        assertEquals(first, resolve())
        assertEquals(entries, cacheDir.list().orEmpty().sorted())

        jar("a-1.0.jar", cls("a/A"), cls("a/Other"))
        val changed = resolve()

        val after = cacheDir.list().orEmpty().sorted()
        assertEquals(2, after.size, "$after")
        assertEquals(1, after.intersect(entries.toSet()).size, "only the entry of the unchanged jar is kept: $entries -> $after")
        assertEquals(emptyList<String>(), deps(changed, "a"))
    }

    @Test
    fun `the identity source of each module is recorded`() {
        jar("lib/plain-1.0.jar", cls("p/P"))
        jar("lib/maven-1.0.jar", cls("m/M"), SyntheticJars.pomProperties("org.m", "maven", "1.0"))

        val byId = modules(resolve())

        assertEquals("file-name", byId.getValue("plain")["identity"])
        assertEquals("pom.properties", byId.getValue("maven")["identity"])
    }

    @Test
    fun `dependencies can be added and removed by hand, and unknown module names fail`() {
        jar("a-1.0.jar", cls("a/A", bodyUses = "b/B"))
        jar("b-1.0.jar", cls("b/B"))
        jar("c-1.0.jar", cls("c/C"))

        val json = resolve(add = listOf("a->c"), remove = listOf("a->b"))

        assertEquals(listOf("c"), deps(json, "a"))
        val unknown = assertThrows(GradleException::class.java) { resolve(add = listOf("a->nope")) }
        assertTrue("nope" in unknown.message.orEmpty() && "a, b, c" in unknown.message.orEmpty(), unknown.message)
        val both = assertThrows(GradleException::class.java) { resolve(add = listOf("a->c"), remove = listOf("a->c")) }
        assertTrue("a->c" in both.message.orEmpty(), both.message)
    }

    @Test
    fun `an added dependency is never the edge that breaks a cycle`() {
        jar("a-1.0.jar", cls("a/A", bodyUses = "b/B"))
        jar("b-1.0.jar", cls("b/B"))

        val json = resolve(add = listOf("b->a"))

        @Suppress("UNCHECKED_CAST")
        val removed = (json["brokenCycles"] as List<Map<String, Any?>>).map { it["removedEdge"] }
        assertEquals(listOf(mapOf("from" to "a", "to" to "b")), removed)
        assertEquals(listOf("a"), deps(json, "b"))
    }

    @Test
    fun `the pom check reports what the code of a pom jar uses but its pom does not list`() {
        jar("lib/target-1.0.jar", cls("t/T"), SyntheticJars.pomProperties("org.t", "target", "1.0"))
        jar("lib/listed-1.0.jar", cls("l/L"), SyntheticJars.pomProperties("org.l", "listed", "1.0"))
        jar(
            "lib/with-pom-1.0.jar", cls("w/W", bodyUses = "t/T"), cls("w/V", bodyUses = "l/L"),
            pom("org.w", "with-pom", "<dependency><groupId>org.l</groupId><artifactId>listed</artifactId><optional>true</optional></dependency>")
        )

        assertNull(resolve()["pomCheck"])
        val json = resolve(pomCheck = true)

        assertEquals(listOf(mapOf("from" to "with-pom", "to" to "target", "references" to 1, "example" to "w.W -> t.T", "added" to false)), json["pomCheck"])
        assertEquals(emptyList<String>(), deps(json, "with-pom"))
        @Suppress("UNCHECKED_CAST")
        assertEquals(emptyList<Any>(), resolve(pomCheck = true, add = listOf("with-pom->target"))["pomCheck"] as List<Any>)
    }
}

