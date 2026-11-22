/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 *
 * Copyright (c) 2026 Edgars Grinvalds <edgars.grinvalds@brain.lv>
 */

package lv.brain.gradle

import groovy.json.JsonSlurper
import lv.brain.gradle.support.GradleEnv
import lv.brain.gradle.support.SyntheticJars
import lv.brain.gradle.support.classpath
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.MethodOrderer
import org.junit.jupiter.api.Order
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.api.TestMethodOrder
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Path
import java.time.Duration
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

/**
 * A delivery shaped like a real vendor SDK: open-source jars from Maven Central (some, like the Spring jars, without
 * an embedded pom), the vendor's own jars without any metadata, a patched copy of an open-source jar and a stray jar
 * nobody mapped. Needs network access to Maven Central; skipped without it.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation::class)
class RealVendorSolutionTest {
    companion object {
        @JvmStatic
        @TempDir
        lateinit var workspace: Path

        private const val CENTRAL = "https://repo.maven.apache.org/maven2"

        private val OPEN_SOURCE = listOf(
            "org.apache.commons:commons-lang3:3.14.0",
            "com.google.guava:guava:33.0.0-jre",
            "com.google.guava:failureaccess:1.0.2",
            "com.fasterxml.jackson.core:jackson-databind:2.16.1",
            "com.fasterxml.jackson.core:jackson-core:2.16.1",
            "com.fasterxml.jackson.core:jackson-annotations:2.16.1",
            "org.springframework:spring-core:6.1.3",
            "org.springframework:spring-jcl:6.1.3",
            "org.springframework:spring-beans:6.1.3",
            "org.springframework:spring-context:6.1.3",
            "org.springframework:spring-aop:6.1.3",
            "org.springframework:spring-expression:6.1.3",
            "org.springframework.boot:spring-boot:3.2.2",
            "org.springframework.boot:spring-boot-autoconfigure:3.2.2"
        )
    }

    private val sdk get() = workspace.resolve("acme-platform-sdk").toFile()
    private val vendor get() = File(sdk, "acme-platform")
    private val cache = File(System.getProperty("user.dir"), "build/test-maven-cache")

    private fun download(coordinate: String): File {
        val (group, artifact, version) = coordinate.split(':')
        val target = File(cache, "$artifact-$version.jar")
        if (target.isFile) return target
        val url = "$CENTRAL/${group.replace('.', '/')}/$artifact/$version/$artifact-$version.jar"
        val response = runCatching {
            HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(20)).followRedirects(HttpClient.Redirect.NORMAL).build()
                .send(HttpRequest.newBuilder(URI(url)).timeout(Duration.ofMinutes(2)).build(), HttpResponse.BodyHandlers.ofByteArray())
        }.getOrNull()
        Assumptions.assumeTrue(response?.statusCode() == 200, "Maven Central is not reachable ($url)")
        cache.mkdirs()
        File(cache, "${target.name}.part").apply { writeBytes(response!!.body()) }.renameTo(target)
        return target
    }

    private fun patched(original: File, target: File) {
        target.parentFile.mkdirs()
        ZipFile(original).use { zip ->
            ZipOutputStream(target.outputStream()).use { out ->
                for (entry in zip.entries()) {
                    out.putNextEntry(ZipEntry(entry.name))
                    zip.getInputStream(entry).use { it.copyTo(out) }
                    out.closeEntry()
                }
                out.putNextEntry(ZipEntry("com/acme/patch/README.txt"))
                out.write("patched by acme\n".toByteArray())
                out.closeEntry()
            }
        }
    }

    @BeforeAll
    fun createVendorDelivery() {
        val lib = File(vendor, "lib").apply { mkdirs() }
        OPEN_SOURCE.forEach { download(it).copyTo(File(lib, download(it).name), overwrite = true) }
        patched(download("commons-io:commons-io:2.15.1"), File(vendor, "lib/patched/commons-io-2.15.1.jar"))

        SyntheticJars.write(
            File(vendor, "modules/acme-core.jar"),
            mapOf(
                "com/acme/platform/Core.class" to SyntheticJars.clazz(
                    "com/acme/platform/Core",
                    publicReturns = "com/fasterxml/jackson/databind/ObjectMapper",
                    bodyUses = "org/apache/commons/lang3/StringUtils"
                )
            )
        )
        SyntheticJars.write(
            File(vendor, "modules/acme-web-2.3.1.jar"),
            mapOf(
                "com/acme/platform/web/WebApp.class" to SyntheticJars.clazz(
                    "com/acme/platform/web/WebApp", publicReturns = "com/acme/platform/Core", bodyUses = "org/springframework/boot/SpringApplication"
                )
            )
        )
        SyntheticJars.write(File(vendor, "ext/legacy-helper.jar"), mapOf("legacy/Helper.class" to SyntheticJars.clazz("legacy/Helper")))

        File(sdk, "vendor.properties").writeText(
            """
            vendor.version=7.4.0
            vendor.dir=acme-platform
            vendor.category.platform=lib, modules, ext
            vendor.compilePackages=com.acme.platform
            vendor.rule.1=^(spring-boot[a-z-]*)-(\d.*)\.jar$ -> org.springframework.boot open-source
            vendor.rule.2=^(spring-[a-z-]+)-(\d.*)\.jar$ -> org.springframework open-source
            vendor.rule.3=^(acme-[a-z-]+?)(?:-(\d[\w.]*))?\.jar$ -> com.acme.platform vendor-private
            """.trimIndent() + "\n"
        )
        File(sdk, "settings.gradle.kts").writeText("plugins { id(\"lv.brain.vendor-distributor\") }\nrootProject.name = \"acme-platform-sdk\"\n")
        File(sdk, "build.gradle.kts").writeText("")
    }

    @Suppress("UNCHECKED_CAST")
    private fun modules(): Map<String, Map<String, Any?>> {
        val json = JsonSlurper().parse(File(sdk, "build/vendor/module-dependencies.json")) as Map<String, Any?>
        return (json["modules"] as List<Map<String, Any?>>).associateBy { it["moduleId"] as String }
    }

    @Suppress("UNCHECKED_CAST")
    private fun dependsOn(module: Map<String, Any?>) = (module["dependsOn"] as List<Map<String, Any?>>).map { it["moduleId"] }

    @Test
    @Order(1)
    fun `rules give the jars without a pom their real coordinates and mark what is open source`() {
        val output = GradleEnv.build(sdk, "packageVendorDistribution").output
        val byId = modules()

        val springCore = byId.getValue("spring-core")
        assertEquals(listOf("org.springframework", "6.1.3", "rule", "open-source"), listOf(springCore["groupId"], springCore["version"], springCore["identity"], springCore["origin"]))
        assertEquals("org.springframework.boot", byId.getValue("spring-boot-autoconfigure")["groupId"])
        assertEquals(listOf("com.acme.platform", "7.4.0", "vendor-private"), byId.getValue("acme-core").let { listOf(it["groupId"], it["version"], it["origin"]) })
        assertEquals("2.3.1", byId.getValue("acme-web")["version"])
        assertEquals("pom.properties", byId.getValue("jackson-databind")["identity"])
        assertEquals("unknown", byId.getValue("jackson-databind")["origin"])
        assertTrue("spring-boot" in dependsOn(byId.getValue("acme-web")) && "acme-core" in dependsOn(byId.getValue("acme-web")), "${byId["acme-web"]}")

        assertTrue("got the default group" in output && "ext/legacy-helper.jar" in output, output)
        assertTrue("spring-core" !in output.substringAfter("got the default group").substringBefore('\n'), output)
    }

    @Test
    @Order(2)
    fun `the Maven check finds the published jars, the patched copy and the published dependencies`() {
        val output = GradleEnv.build(sdk, "verifyVendorAgainstMaven").output
        @Suppress("UNCHECKED_CAST")
        val report = JsonSlurper().parse(File(sdk, "build/reports/vendor-maven-check/report.json")) as Map<String, Any?>
        @Suppress("UNCHECKED_CAST")
        val status = (report["jars"] as List<Map<String, Any?>>).associate { it["module"] to it["status"] }

        assertEquals("match", status["spring-core"], "$status")
        assertEquals("match", status["spring-boot"], "$status")
        assertEquals("match", status["commons-lang3"], "$status")
        assertEquals("match", status["jackson-databind"], "$status")
        assertEquals("differs", status["commons-io"], "$status")
        @Suppress("UNCHECKED_CAST")
        val skipped = report["skipped"] as List<String>
        assertTrue("acme-core" in skipped && "acme-web" in skipped && "legacy-helper" in skipped, "$skipped")
        @Suppress("UNCHECKED_CAST")
        val dependencies = (report["dependencies"] as List<Map<String, Any?>>).associateBy { it["module"] }
        assertEquals(emptyList<Any>(), dependencies["spring-boot"]?.get("missing") ?: emptyList<Any>(), "${dependencies["spring-boot"]}")
        assertTrue("lib/patched/commons-io-2.15.1.jar (commons-io:commons-io:2.15.1) is not the published jar" in output, output)
    }

    @Test
    @Order(3)
    fun `an application compiles against the vendor API only and runs with everything`() {
        val app = workspace.resolve("app").toFile().apply { mkdirs() }
        File(app, "vendor.properties").writeText(
            "vendor.version=7.4.0\nvendor.cacheMode=project\nvendor.localZip=../acme-platform-sdk/build/distributions/vendor-sdk-7.4.0.zip\n"
        )
        File(app, "settings.gradle.kts").writeText("plugins { id(\"lv.brain.vendor-consumer\") }\nrootProject.name = \"app\"\n")
        File(app, "build.gradle.kts").writeText(
            """
            plugins { java }
            dependencies { implementation(vendor.bundle("platform")) }
            tasks.register("printClasspaths") {
                val compileFiles = configurations.compileClasspath.get().incoming.files
                val runtimeFiles = configurations.runtimeClasspath.get().incoming.files
                doLast {
                    println("COMPILE=" + compileFiles.files.map { it.name }.sorted())
                    println("RUNTIME=" + runtimeFiles.files.map { it.name }.sorted())
                }
            }
            """.trimIndent() + "\n"
        )

        val output = GradleEnv.build(app, "printClasspaths").output
        val compile = classpath(output, "COMPILE")
        val runtime = classpath(output, "RUNTIME")

        assertTrue("acme-core.jar" in compile && "acme-web-2.3.1.jar" in compile && "jackson-databind-2.16.1.jar" in compile, "$compile")
        assertTrue("commons-lang3-3.14.0.jar" !in compile && "spring-boot-3.2.2.jar" !in compile, "$compile")
        assertTrue("commons-lang3-3.14.0.jar" in runtime && "spring-boot-3.2.2.jar" in runtime && "spring-core-6.1.3.jar" in runtime, "$runtime")
    }
}
