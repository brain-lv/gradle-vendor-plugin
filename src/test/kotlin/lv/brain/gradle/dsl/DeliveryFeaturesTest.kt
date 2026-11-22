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
import org.objectweb.asm.Opcodes
import java.io.File

/** What a delivery needs: checks of the vendor jars, the bill of materials, checksums, vendor files, a Maven export and a diff. */
@Tag("gradle-matrix")
class DeliveryFeaturesTest {

    @TempDir
    lateinit var tmp: File

    private val sdk get() = File(tmp, "sdk")
    private val app get() = File(tmp, "app").apply { mkdirs() }

    private fun createSdk(version: String = "2026.1.0", apiReturns: String? = "com/acme/Model", newJava: Boolean = false) {
        SyntheticJars.write(
            File(sdk, "vendor/lib/acme-api-1.0.jar"),
            mapOf(
                "com/acme/Api.class" to SyntheticJars.clazz("com/acme/Api", publicReturns = apiReturns, bodyUses = "org/lite/Lite"),
                "com/acme/Model.class" to SyntheticJars.clazz("com/acme/Model")
            )
        )
        SyntheticJars.write(
            File(sdk, "vendor/lib/commons-lite-3.8.jar"),
            mapOf("org/lite/Lite.class" to SyntheticJars.clazz("org/lite/Lite", bodyUses = "org/slf4j/Logger"))
        )
        if (newJava) {
            SyntheticJars.write(File(sdk, "vendor/lib/modern-1.0.jar"), mapOf("m/M.class" to SyntheticJars.clazz("m/M", classVersion = Opcodes.V21)))
        }
        File(sdk, "vendor/conf/app.properties").apply { parentFile.mkdirs() }.writeText("mode=prod\n")
        File(sdk, "vendor.properties").writeText("vendor.version=$version\n")
        File(sdk, "settings.gradle.kts").writeText("plugins { id(\"lv.brain.vendor-distributor\") }\nrootProject.name = \"vendor-sdk\"\n")
        File(sdk, "build.gradle.kts").writeText("")
    }

    private fun consumerSettings(extra: String = "") {
        File(app, "vendor.properties").writeText(
            "vendor.version=2026.1.0\nvendor.cacheMode=project\nvendor.localZip=../sdk/build/distributions/vendor-sdk-2026.1.0.zip\n$extra"
        )
        File(app, "settings.gradle.kts").writeText("plugins { id(\"lv.brain.vendor-consumer\") }\nrootProject.name = \"app\"\n")
    }

    @Test
    fun `the distribution carries a bill of materials and a checksum, and reports classes that no jar has`() {
        createSdk()

        val output = GradleEnv.build(sdk, "packageVendorDistribution").output

        assertTrue("are used by vendor jars but are in no vendor jar and not in the JDK" in output && "org.slf4j" in output, output)
        val zip = File(sdk, "build/distributions/vendor-sdk-2026.1.0.zip")
        val sbom = String(zipEntries(zip).getValue("vendor-sbom.cdx.json"))
        assertTrue("\"bomFormat\": \"CycloneDX\"" in sbom && "commons-lite" in sbom && "SHA-256" in sbom, sbom)
        val checksum = File(sdk, "build/distributions/vendor-sdk-2026.1.0.zip.sha256").readText()
        assertTrue(checksum.endsWith("  vendor-sdk-2026.1.0.zip\n") && checksum.substringBefore(' ').length == 64, checksum)
    }

    @Test
    fun `the HTML report shows every jar, why it depends on another and what needs attention`() {
        createSdk()

        val output = GradleEnv.build(sdk, "vendorReport").output

        assertTrue("Vendor report: file:" in output, output)
        val html = File(sdk, "build/reports/vendor/index.html").readText()
        assertTrue("id=\"m-acme-api\"" in html && "id=\"m-commons-lite\"" in html, html)
        assertTrue("com.acme.Api references org.lite.Lite" in html, html)
        assertTrue("packages of classes that vendor jars use but no jar has" in html && "org.slf4j" in html, html)
        assertTrue("<script>" in html && "http" !in html.substringAfter("<body>"), "the page needs no network")
    }

    @Test
    fun `a consumer checks the checksum and copies vendor files into its installation`() {
        createSdk()
        GradleEnv.build(sdk, "packageVendorDistribution")
        val sha = File(sdk, "build/distributions/vendor-sdk-2026.1.0.zip.sha256").readText().substringBefore(' ')

        consumerSettings("vendor.sha256=${"0".repeat(64)}\n")
        File(app, "build.gradle.kts").writeText("plugins { java }\n")
        val rejected = GradleEnv.buildAndFail(app, "help").output
        assertTrue("vendor.sha256 expects" in rejected && "was not unpacked" in rejected, rejected)

        consumerSettings("vendor.sha256=$sha\n")
        File(app, "build.gradle.kts").writeText(
            """
            plugins { java }
            dependencies { implementation(vendor.bundle("core")) }
            tasks.register<Copy>("install") {
                from(vendor.files("conf")) { into("conf") }
                into(layout.buildDirectory.dir("install"))
            }
            """.trimIndent() + "\n"
        )
        GradleEnv.build(app, "install")

        assertEquals("mode=prod\n", File(app, "build/install/conf/app.properties").readText())
    }

    @Test
    fun `Gradle refuses a vendor jar that needs a newer Java than the project targets`() {
        createSdk(newJava = true)
        GradleEnv.build(sdk, "packageVendorDistribution")
        consumerSettings()
        File(app, "build.gradle.kts").writeText(
            """
            plugins { java }
            java { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
            dependencies { implementation(vendor.module(name = "modern")) }
            tasks.register("printClasspaths") {
                val files = configurations.compileClasspath.get().incoming.files
                doLast { println("COMPILE=" + files.files.map { it.name }.sorted()) }
            }
            """.trimIndent() + "\n"
        )

        val output = GradleEnv.buildAndFail(app, "printClasspaths").output

        assertTrue("lv.brain.vendor.core:modern:1.0" in output, output)
        assertTrue(Regex("(Java|JVM runtime version) 21").containsMatchIn(output) && Regex("(Java|JVM runtime version) 17").containsMatchIn(output), output)
    }

    @Test
    fun `classes a vendor jar shares with a library of the project are reported`() {
        createSdk()
        GradleEnv.build(sdk, "packageVendorDistribution")
        val repo = File(tmp, "repo/org/lite/lite-copy/1.0").apply { mkdirs() }
        File(repo, "lite-copy-1.0.pom").writeText(
            "<project><modelVersion>4.0.0</modelVersion><groupId>org.lite</groupId><artifactId>lite-copy</artifactId><version>1.0</version></project>\n"
        )
        SyntheticJars.write(File(repo, "lite-copy-1.0.jar"), mapOf("org/lite/Lite.class" to SyntheticJars.clazz("org/lite/Lite")))
        consumerSettings()
        File(app, "build.gradle.kts").writeText(
            """
            plugins { java }
            repositories { maven { url = uri("../repo") } }
            dependencies {
                implementation(vendor.bundle("core"))
                implementation("org.lite:lite-copy:1.0")
            }
            """.trimIndent() + "\n"
        )

        val output = GradleEnv.build(app, "verifyVendorClasspath").output

        assertTrue("commons-lite-3.8.jar (vendor) and lite-copy-1.0.jar: 1 class(es), e.g. org.lite.Lite" in output, output)
    }

    @Test
    fun `the Maven export is published to a folder and works without the consumer plugin`() {
        createSdk()
        val target = File(tmp, "published")
        File(sdk, "vendor.properties").appendText("vendor.publish.url=${target.toURI()}\n")

        GradleEnv.build(sdk, "publishVendorMavenRepository")

        val plain = File(tmp, "plain").apply { mkdirs() }
        File(plain, "settings.gradle.kts").writeText("rootProject.name = \"plain\"\n")
        File(plain, "build.gradle.kts").writeText(
            """
            plugins { java }
            repositories { maven { url = uri("${target.toURI()}") } }
            dependencies { implementation("lv.brain.vendor.bundle:core:2026.1.0") }
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
        val output = GradleEnv.build(plain, "printClasspaths").output

        assertEquals(listOf("acme-api-1.0.jar", "commons-lite-3.8.jar"), classpath(output, "RUNTIME"), output)
        assertEquals(listOf("acme-api-1.0.jar", "commons-lite-3.8.jar"), classpath(output, "COMPILE"), output)
    }

    @Test
    fun `the diff names removed public API, changed jars and new dependencies`() {
        createSdk(version = "2026.1.0")
        GradleEnv.build(sdk, "packageVendorDistribution")
        val baseline = File(tmp, "vendor-sdk-2026.1.0.zip")
        File(sdk, "build/distributions/vendor-sdk-2026.1.0.zip").copyTo(baseline)

        createSdk(version = "2026.2.0", apiReturns = null)
        val output = GradleEnv.build(sdk, "diffVendorDistribution", "--baseline=${baseline.absolutePath}").output

        assertTrue("1 changed (1 with removed public API)" in output, output)
        val report = File(sdk, "build/reports/vendor-diff/diff.txt").readText()
        assertTrue("method com.acme.Api.leak()Lcom/acme/Model;" in report && "content: changed" in report, report)
    }
}
