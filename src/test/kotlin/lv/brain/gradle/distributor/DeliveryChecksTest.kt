/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 *
 * Copyright (c) 2026 Edgars Grinvalds <edgars.grinvalds@brain.lv>
 */

package lv.brain.gradle.distributor

import groovy.json.JsonSlurper
import lv.brain.gradle.consumer.VendorDuplicateClasses
import lv.brain.gradle.support.SyntheticJars
import org.gradle.api.GradleException
import org.gradle.testfixtures.ProjectBuilder
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.objectweb.asm.Opcodes
import java.io.File

class DeliveryChecksTest {

    @TempDir
    lateinit var tempDir: File

    private val vendorDir get() = File(tempDir, "vendor")

    private fun jar(path: String, vararg entries: Pair<String, ByteArray>) = SyntheticJars.write(File(vendorDir, path), entries.toMap())

    private fun cls(name: String, bodyUses: String? = null, classVersion: Int = Opcodes.V17) =
        "$name.class" to SyntheticJars.clazz(name, bodyUses = bodyUses, classVersion = classVersion)

    private fun graphFile() = File(tempDir, "build/module-dependencies.json")

    @Suppress("UNCHECKED_CAST")
    private fun resolve(
        missing: String = "warn",
        pomCheck: String = "off",
        excludeClasses: List<String> = emptyList(),
        scope: String = "vendor",
        allow: List<String> = emptyList(),
        deny: List<String> = emptyList()
    ): Map<String, Any?> {
        val project = ProjectBuilder.builder().withProjectDir(tempDir).build()
        val task = project.tasks.register("resolveDeps", ResolveModuleDependenciesTask::class.java) {
            this.vendorDir.set(this@DeliveryChecksTest.vendorDir)
            this.dependencyGraphFile.set(graphFile())
            this.missingClasses.set(missing)
            this.pomCheck.set(pomCheck)
            this.dependencyExcludeClasses.set(excludeClasses)
            this.missingClassesScope.set(scope)
            this.licenseAllow.set(allow)
            this.licenseDeny.set(deny)
        }.get()
        task.resolveDependencies()
        return JsonSlurper().parse(graphFile()) as Map<String, Any?>
    }

    @Suppress("UNCHECKED_CAST")
    private fun module(json: Map<String, Any?>, id: String): Map<String, Any?> =
        (json["modules"] as List<Map<String, Any?>>).single { it["moduleId"] == id }

    @Test
    fun `classes that are in no vendor jar and not in the JDK are reported, and fail on request`() {
        jar("a-1.0.jar", cls("a/A", bodyUses = "org/slf4j/Logger"), cls("a/B", bodyUses = "javax/swing/JFrame"), cls("a/C", bodyUses = "b/B"))
        jar("b-1.0.jar", cls("b/B", bodyUses = "org/optional/Thing"))

        val json = resolve()

        assertEquals(
            listOf(
                mapOf("package" to "org.optional", "classCount" to 1, "classes" to listOf("org.optional.Thing"), "usedBy" to listOf("b"), "openSourceOnly" to false),
                mapOf("package" to "org.slf4j", "classCount" to 1, "classes" to listOf("org.slf4j.Logger"), "usedBy" to listOf("a"), "openSourceOnly" to false)
            ),
            json["missingClasses"]
        )
        @Suppress("UNCHECKED_CAST")
        assertEquals(listOf("org.slf4j"), (resolve(excludeClasses = listOf("org.optional"))["missingClasses"] as List<Map<String, Any?>>).map { it["package"] })
        assertNull(resolve(missing = "off")["missingClasses"])
        val failure = assertThrows(GradleException::class.java) { resolve(missing = "fail") }
        assertTrue("org.slf4j" in failure.message.orEmpty() && "vendor.missingClasses" in failure.message.orEmpty(), failure.message)
    }

    @Test
    fun `each module records its Java version, its SHA-256 and the licenses of its pom`() {
        jar("new-1.0.jar", cls("n/N", classVersion = Opcodes.V21), cls("n/Old", classVersion = Opcodes.V1_8))
        jar(
            "lic-1.0.jar", cls("l/L"),
            "META-INF/maven/org.l/lic/pom.xml" to (
                "<project><groupId>org.l</groupId><artifactId>lic</artifactId><version>1.0</version>" +
                    "<licenses><license><name>Apache-2.0</name></license></licenses></project>"
                ).toByteArray()
        )

        val json = resolve()

        assertEquals(21, module(json, "new")["javaVersion"])
        assertEquals(17, module(json, "lic")["javaVersion"])
        assertEquals(listOf("Apache-2.0"), module(json, "lic")["licenses"])
        assertEquals(RawReferenceScanner.sha256(File(vendorDir, "new-1.0.jar")), module(json, "new")["sha256"])
    }

    @Test
    fun `the generated module metadata declares the Java version, so Gradle refuses a jar that is too new`() {
        jar("new-1.0.jar", cls("n/N", classVersion = Opcodes.V21))
        resolve()
        val ivyDir = File(vendorDir, "ivy")
        IvyRepositoryGenerator.generate(VendorIvyDependencyMapper.loadGraph(graphFile()), vendorDir, ivyDir, "2026.1.0", "lv.brain.vendor.bundle", slimJars = false)

        val metadata = File(ivyDir, "lv.brain.vendor.core/new-1.0/new-1.0.module").readText()

        assertEquals(2, Regex("\"org.gradle.jvm.version\": 21").findAll(metadata).count(), metadata)
        JsonSlurper().parseText(metadata)
    }

    @Test
    fun `pomCheck add adds the dependencies a pom leaves out`() {
        jar("t-1.0.jar", cls("t/T"))
        jar(
            "w-1.0.jar", cls("w/W", bodyUses = "t/T"),
            "META-INF/maven/org.w/w/pom.xml" to "<project><groupId>org.w</groupId><artifactId>w</artifactId><version>1.0</version></project>".toByteArray()
        )

        val json = resolve(pomCheck = "add")

        @Suppress("UNCHECKED_CAST")
        assertEquals(listOf("t"), (module(json, "w")["dependsOn"] as List<Map<String, Any?>>).map { it["moduleId"] })
        assertEquals("maven-pom+pom-check", module(json, "w")["resolutionSource"])
        @Suppress("UNCHECKED_CAST")
        assertEquals(true, (json["pomCheck"] as List<Map<String, Any?>>).single()["added"])
    }

    @Test
    fun `the bill of materials lists every jar with hash, license, purl and dependencies`() {
        jar("t-1.0.jar", cls("t/T"), SyntheticJars.pomProperties("org.t", "t", "1.0"))
        jar("plain-2.0.jar", cls("p/P", bodyUses = "t/T"))
        resolve()

        val sbom = VendorSbom.render(VendorIvyDependencyMapper.loadGraph(graphFile()), "lv.brain.vendor", "2026.1.0")

        @Suppress("UNCHECKED_CAST")
        val components = (sbom["components"] as List<Map<String, Any?>>).associateBy { it["name"] }
        assertEquals("pkg:maven/org.t/t@1.0", components.getValue("t")["purl"])
        assertNull(components.getValue("plain")["purl"])
        @Suppress("UNCHECKED_CAST")
        assertEquals("SHA-256", (components.getValue("t")["hashes"] as List<Map<String, Any?>>).single()["alg"])
        assertEquals(
            mapOf("ref" to "lv.brain.vendor.core:plain:2.0", "dependsOn" to listOf("org.t:t:1.0")),
            (sbom["dependencies"] as List<*>).first { (it as Map<*, *>)["ref"] == "lv.brain.vendor.core:plain:2.0" }
        )
    }

    @Test
    fun `the Maven export has jars, poms, module metadata, metadata files and checksums`() {
        jar("t-1.0.jar", cls("t/T"), SyntheticJars.pomProperties("org.t", "t", "1.0"))
        jar("plain-2.0.jar", cls("p/P", bodyUses = "t/T"))
        resolve()
        val ivyDir = File(vendorDir, "ivy")
        val modules = VendorIvyDependencyMapper.loadGraph(graphFile())
        IvyRepositoryGenerator.generate(modules, vendorDir, ivyDir, "2026.1.0", "lv.brain.vendor.bundle", slimJars = true)
        val out = File(tempDir, "repo")

        assertEquals(3, VendorMavenLayout.write(modules, vendorDir, ivyDir, out, "2026.1.0", "lv.brain.vendor.bundle"))

        val dir = File(out, "lv/brain/vendor/core/plain/2.0")
        listOf("plain-2.0.jar", "plain-2.0-slim.jar", "plain-2.0.pom", "plain-2.0.module", "plain-2.0.jar.sha1", "plain-2.0.pom.sha256")
            .forEach { assertTrue(File(dir, it).isFile, "$it: ${dir.list()?.toList()}") }
        val pom = File(dir, "plain-2.0.pom").readText()
        assertTrue("<artifactId>t</artifactId>" in pom && "do_not_remove: published-with-gradle-metadata" in pom, pom)
        assertTrue(File(out, "lv/brain/vendor/bundle/core/2026.1.0/core-2026.1.0.pom").readText().contains("<packaging>pom</packaging>"))
        assertTrue(File(out, "org/t/t/maven-metadata.xml").readText().contains("<release>1.0</release>"))
        @Suppress("UNCHECKED_CAST")
        val variants = (JsonSlurper().parse(File(dir, "plain-2.0.module")) as Map<String, Any?>)["variants"] as List<Map<String, Any?>>
        @Suppress("UNCHECKED_CAST")
        assertEquals("plain-2.0-slim.jar", ((variants.first()["files"] as List<Map<String, Any?>>).single())["url"])
    }

    @Test
    fun `the public API of a jar lists classes, methods and fields`() {
        jar("a-1.0.jar", "a/A.class" to SyntheticJars.clazz("a/A", publicReturns = "b/B"))

        val api = File(vendorDir, "a-1.0.jar").inputStream().use { DiffVendorDistributionTask.api(it) }

        assertTrue("class a.A" in api && "method a.A.leak()Lb/B;" in api, "$api")
    }

    @Test
    fun `classes a vendor jar shares with another jar of the classpath are found`() {
        jar("lib/lite-3.8.jar", cls("org/lite/Lite"), cls("org/lite/Only"))
        jar("own-1.0.jar", cls("own/O"))
        val other = File(tempDir, "cache/lite-3.12.jar").also { SyntheticJars.write(it, mapOf(cls("org/lite/Lite"))) }
        val unrelated = File(tempDir, "cache/x.jar").also { SyntheticJars.write(it, mapOf(cls("own/O"), cls("x/X"))) }

        val shared = VendorDuplicateClasses.find(listOf(File(vendorDir, "lib/lite-3.8.jar"), File(vendorDir, "own-1.0.jar"), other, unrelated), vendorDir)

        assertEquals(listOf(listOf("lite-3.12.jar", "lite-3.8.jar"), listOf("own-1.0.jar", "x.jar")), shared.map { group -> group.jars.map { it.name } })
        assertEquals(listOf("org/lite/Lite"), shared.first().classes)
        assertTrue("lite-3.8.jar (vendor)" in VendorDuplicateClasses.message("project ':app'", shared))
    }

    @Test
    fun `classes only open-source jars use are counted but do not fail, unless every class counts`() {
        jar("lib/lib-1.0.jar", cls("org/lib/L", bodyUses = "ch/qos/logback/Logger"), SyntheticJars.pomProperties("org.lib", "lib", "1.0"))
        jar("own-1.0.jar", cls("own/O", bodyUses = "org/lib/L"))

        @Suppress("UNCHECKED_CAST")
        val missing = resolve(missing = "fail")["missingClasses"] as List<Map<String, Any?>>

        assertEquals(listOf(true), missing.map { it["openSourceOnly"] })
        val failure = assertThrows(GradleException::class.java) { resolve(missing = "fail", scope = "all") }
        assertTrue("ch.qos.logback" in failure.message.orEmpty(), failure.message)
    }

    @Test
    fun `the licence policy fails on a denied licence and lists jars without one`() {
        fun pomWith(license: String) =
            "<project><groupId>org.l</groupId><artifactId>lic</artifactId><version>1.0</version><licenses><license><name>$license</name></license></licenses></project>"
        jar("lic-1.0.jar", cls("l/L"), "META-INF/maven/org.l/lic/pom.xml" to pomWith("GNU General Public License v3.0").toByteArray())
        jar("plain-1.0.jar", cls("p/P"))

        val failure = assertThrows(GradleException::class.java) { resolve(deny = listOf("GPL-*")) }
        assertTrue("lic-1.0.jar (org.l:lic:1.0): GNU General Public License v3.0 [GPL-3.0]" in failure.message.orEmpty(), failure.message)

        @Suppress("UNCHECKED_CAST")
        val policy = resolve(allow = listOf("GPL-3.0"))["licensePolicy"] as Map<String, Any?>
        assertEquals(listOf("plain"), policy["unknown"])
        assertEquals(listOf("GPL-3.0"), module(resolve(), "lic")["licenseIds"])
    }
}

