/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 *
 * Copyright (c) 2026 Edgars Grinvalds <edgars.grinvalds@brain.lv>
 */

package lv.brain.gradle.dsl

import lv.brain.gradle.core.VendorSignature
import lv.brain.gradle.support.GradleEnv
import lv.brain.gradle.support.SyntheticJars
import lv.brain.gradle.support.ZipServer
import lv.brain.gradle.support.classpath
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.util.Base64

/** Signed distributions, and a consumer build that uses distributions of several vendors. */
@Tag("gradle-matrix")
class ReleaseSafetyFeaturesTest {

    @TempDir
    lateinit var tmp: File

    private fun createSdk(dir: File, artifact: String, properties: String = "") {
        SyntheticJars.write(
            File(dir, "vendor/lib/$artifact-1.0.jar"),
            mapOf("com/$artifact/Main.class" to SyntheticJars.clazz("com/$artifact/Main"))
        )
        File(dir, "vendor.properties").writeText("vendor.version=2026.1.0\n$properties")
        File(dir, "settings.gradle.kts").writeText("plugins { id(\"lv.brain.vendor-distributor\") }\nrootProject.name = \"vendor-sdk\"\n")
        File(dir, "build.gradle.kts").writeText("")
    }

    private fun app(properties: String, dependencies: String): File {
        val app = File(tmp, "app").apply { mkdirs() }
        File(app, "vendor.properties").writeText(properties)
        File(app, "settings.gradle.kts").writeText("plugins { id(\"lv.brain.vendor-consumer\") }\nrootProject.name = \"app\"\n")
        File(app, "build.gradle.kts").writeText(
            """
            plugins { java }
            dependencies { $dependencies }
            tasks.register("printClasspaths") {
                val runtimeFiles = configurations.runtimeClasspath.get().incoming.files
                doLast { println("RUNTIME=" + runtimeFiles.files.map { it.name }.sorted()) }
            }
            """.trimIndent() + "\n"
        )
        return app
    }

    private fun publicKeyLine(sdk: File) =
        Base64.getEncoder().encodeToString(VendorSignature.der(File(sdk, "build/vendor-signing/vendor-signing.pub").readText()))

    @Test
    fun `a signed distribution is unpacked only with a valid signature`() {
        val sdk = File(tmp, "sdk")
        createSdk(sdk, "acme")
        GradleEnv.build(sdk, "generateVendorSigningKey")
        File(sdk, "vendor.properties").appendText("vendor.signing.key=build/vendor-signing/vendor-signing.key\n")
        GradleEnv.build(sdk, "packageVendorDistribution")
        val zip = File(sdk, "build/distributions/vendor-sdk-2026.1.0.zip")
        assertTrue(File(zip.path + ".sig").isFile)
        val key = publicKeyLine(sdk)

        val local = "vendor.version=2026.1.0\nvendor.cacheMode=project\nvendor.localZip=../sdk/build/distributions/vendor-sdk-2026.1.0.zip\n"
        val app = app("${local}vendor.signing.publicKey=$key\n", "implementation(vendor.bundle(\"core\"))")
        assertEquals(listOf("acme-1.0.jar"), classpath(GradleEnv.build(app, "printClasspaths").output, "RUNTIME"))

        val other = VendorSignature.generate().second.let { Base64.getEncoder().encodeToString(VendorSignature.der(it)) }
        File(app, ".vendor").deleteRecursively()
        File(app, "vendor.properties").writeText("${local}vendor.signing.publicKey=$other\n")
        val rejected = GradleEnv.buildAndFail(app, "printClasspaths").output
        assertTrue("does not match vendor.signing.publicKey" in rejected, rejected)
    }

    @Test
    fun `a downloaded distribution brings its signature`() {
        val sdk = File(tmp, "sdk")
        createSdk(sdk, "acme")
        GradleEnv.build(sdk, "generateVendorSigningKey")
        File(sdk, "vendor.properties").appendText("vendor.signing.key=build/vendor-signing/vendor-signing.key\n")
        GradleEnv.build(sdk, "packageVendorDistribution")
        val zip = File(sdk, "build/distributions/vendor-sdk-2026.1.0.zip")

        ZipServer(zip, "/dist/vendor-sdk-2026.1.0.zip").use { server ->
            val app = app(
                "vendor.version=2026.1.0\nvendor.cacheMode=project\nvendor.url=${server.urlPattern}\nvendor.signing.publicKey=${publicKeyLine(sdk)}\n",
                "implementation(vendor.bundle(\"core\"))"
            )
            val output = GradleEnv.build(app, "printClasspaths").output

            assertEquals(listOf("acme-1.0.jar"), classpath(output, "RUNTIME"), output)
            assertTrue(server.requests.any { it.endsWith(".zip.sig") }, "${server.requests}")
        }
    }

    @Test
    fun `one build uses the distributions of two vendors side by side`() {
        createSdk(File(tmp, "acme"), "acme")
        createSdk(File(tmp, "beta"), "beta", "vendor.defaultGroup=com.beta\n")
        GradleEnv.build(File(tmp, "acme"), "packageVendorDistribution")
        GradleEnv.build(File(tmp, "beta"), "packageVendorDistribution")

        val app = app(
            """
            vendor.cacheMode=project
            vendor.distributions=acme, beta
            vendor.acme.version=2026.1.0
            vendor.acme.localZip=../acme/build/distributions/vendor-sdk-2026.1.0.zip
            vendor.beta.version=2026.1.0
            vendor.beta.defaultGroup=com.beta
            vendor.beta.localZip=../beta/build/distributions/vendor-sdk-2026.1.0.zip
            """.trimIndent() + "\n",
            "implementation(vendor.from(\"acme\").bundle(\"core\")); implementation(vendor.from(\"beta\").bundle(\"core\"))"
        )
        val output = GradleEnv.build(app, "printClasspaths").output

        assertEquals(listOf("acme-1.0.jar", "beta-1.0.jar"), classpath(output, "RUNTIME"), output)
        assertTrue(File(app, ".vendor/acme/2026.1.0").isDirectory && File(app, ".vendor/beta/2026.1.0").isDirectory)

        File(app, "build.gradle.kts").writeText("plugins { java }\ndependencies { implementation(vendor.bundle(\"core\")) }\n")
        val noDefault = GradleEnv.buildAndFail(app, "dependencies").output
        assertTrue("no default vendor distribution" in noDefault && "vendor.from(\"acme\")" in noDefault, noDefault)
    }

    @Test
    fun `two distributions that publish one module with different content are refused`() {
        createSdk(File(tmp, "acme"), "acme")
        createSdk(File(tmp, "beta"), "beta")
        GradleEnv.build(File(tmp, "acme"), "packageVendorDistribution")
        GradleEnv.build(File(tmp, "beta"), "packageVendorDistribution")

        val app = app(
            """
            vendor.cacheMode=project
            vendor.version=2026.1.0
            vendor.localZip=../acme/build/distributions/vendor-sdk-2026.1.0.zip
            vendor.distributions=beta
            vendor.beta.version=2026.1.0
            vendor.beta.localZip=../beta/build/distributions/vendor-sdk-2026.1.0.zip
            """.trimIndent() + "\n",
            "implementation(vendor.bundle(\"core\"))"
        )
        val output = GradleEnv.buildAndFail(app, "printClasspaths").output

        assertTrue("publish the same module with different content" in output, output)
        assertTrue("lv.brain.vendor.bundle:core:2026.1.0 in default, beta" in output, output)
    }
}
