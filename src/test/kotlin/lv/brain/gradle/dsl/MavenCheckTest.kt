/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 *
 * Copyright (c) 2026 Edgars Grinvalds <edgars.grinvalds@brain.lv>
 */

package lv.brain.gradle.dsl

import groovy.json.JsonSlurper
import lv.brain.gradle.support.GradleEnv
import lv.brain.gradle.support.SyntheticJars
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.security.MessageDigest

/** verifyVendorAgainstMaven against a repository on disk: hashes, published dependencies and vendor code. */
@Tag("gradle-matrix")
class MavenCheckTest {

    @TempDir
    lateinit var tmp: File

    private val sdk get() = File(tmp, "sdk")
    private val repo get() = File(tmp, "repo")

    private fun sha1(file: File) = MessageDigest.getInstance("SHA-1").digest(file.readBytes()).joinToString("") { "%02x".format(it) }

    private fun publish(
        group: String,
        artifact: String,
        version: String,
        jar: File?,
        dependencies: List<String> = emptyList(),
        sha1: String? = null,
        license: String = "Apache License, Version 2.0"
    ) {
        val dir = File(repo, "${group.replace('.', '/')}/$artifact/$version").apply { mkdirs() }
        File(dir, "$artifact-$version.jar.sha1").writeText((sha1 ?: sha1(jar!!)) + "\n")
        File(dir, "$artifact-$version.pom").writeText(
            "<project><groupId>$group</groupId><artifactId>$artifact</artifactId><version>$version</version>" +
                "<licenses><license><name>$license</name></license></licenses><dependencies>" +
                dependencies.joinToString("") { "<dependency><groupId>${it.substringBefore(':')}</groupId><artifactId>${it.substringAfter(':')}</artifactId><version>1</version></dependency>" } +
                "</dependencies></project>"
        )
    }

    @Test
    fun `published jars match, changed and unknown jars are named, and failOn makes them errors`() {
        val util = File(sdk, "vendor/lib/util-2.0.jar")
        SyntheticJars.write(util, mapOf("org/util/U.class" to SyntheticJars.clazz("org/util/U")))
        val web = File(sdk, "vendor/lib/web-1.0.jar")
        SyntheticJars.write(web, mapOf("org/web/W.class" to SyntheticJars.clazz("org/web/W")))
        SyntheticJars.write(File(sdk, "vendor/lib/core-1.0.jar"), mapOf("org/core/C.class" to SyntheticJars.clazz("org/core/C")))
        SyntheticJars.write(File(sdk, "vendor/lib/acme-1.0.jar"), mapOf("com/acme/A.class" to SyntheticJars.clazz("com/acme/A", bodyUses = "org/web/W")))
        val sdkApi = File(sdk, "vendor/lib/acme-sdk-api-4.0.jar")
        SyntheticJars.write(sdkApi, mapOf("com/acme/api/Api.class" to SyntheticJars.clazz("com/acme/api/Api")))
        val maven = File(sdk, "vendor/lib/thing-3.0.jar")
        SyntheticJars.write(maven, mapOf("org/thing/T.class" to SyntheticJars.clazz("org/thing/T"), SyntheticJars.pomProperties("org.thing", "thing", "3.0")))
        SyntheticJars.write(File(sdk, "vendor/misc/mystery.jar"), mapOf("m/M.class" to SyntheticJars.clazz("m/M")))
        publish("com.acme.public", "acme-sdk-api", "4.0", sdkApi)
        publish("org.thing", "thing", "3.0", maven)
        publish("org.oss", "util", "2.0", util, license = "GNU Affero General Public License v3")
        publish("org.oss", "web", "1.0", web, dependencies = listOf("org.oss:util"))
        publish("org.oss", "core", "1.0", null, sha1 = "0".repeat(40))

        File(sdk, "vendor.properties").writeText(
            """
            vendor.version=2026.1.0
            vendor.rule.1=^(acme)-(\d.*)\.jar$ -> com.acme vendor-private
            vendor.rule.2=^(acme-sdk-api)-(\d.*)\.jar$ -> com.acme.public vendor-public
            vendor.rule.3=^(util|web|core)-(\d.*)\.jar$ -> org.oss oss
            vendor.mavenCheck.url=${repo.toURI()}
            vendor.licenses.deny=AGPL-*
            vendor.licenses.unknown=off
            """.trimIndent() + "\n"
        )
        File(sdk, "settings.gradle.kts").writeText("plugins { id(\"lv.brain.vendor-distributor\") }\nrootProject.name = \"vendor-sdk\"\n")
        File(sdk, "build.gradle.kts").writeText("")

        val output = GradleEnv.build(sdk, "verifyVendorAgainstMaven").output

        @Suppress("UNCHECKED_CAST")
        val report = JsonSlurper().parse(File(sdk, "build/reports/vendor-maven-check/report.json")) as Map<String, Any?>
        @Suppress("UNCHECKED_CAST")
        val status = (report["jars"] as List<Map<String, Any?>>).associate { it["module"] to it["status"] }
        assertEquals(mapOf("acme-sdk-api" to "match", "core" to "differs", "thing" to "match", "util" to "match", "web" to "match"), status)
        assertEquals(listOf("acme", "mystery"), report["skipped"])
        assertEquals(listOf(mapOf("module" to "web", "missing" to listOf("util"), "extra" to emptyList<String>())), report["dependencies"])
        assertTrue("1 published dependency(ies) the graph does not reach" in output, output)
        assertEquals(listOf(mapOf("module" to "util", "licenses" to listOf("GNU Affero General Public License v3"))), report["licenses"])

        File(sdk, "vendor.properties").appendText("vendor.mavenCheck.failOn=differs, dependencies, licenses\n")
        val failed = GradleEnv.buildAndFail(sdk, "verifyVendorAgainstMaven").output
        assertTrue(
            "1 jar(s) differ from the published ones; 1 published dependency(ies) are not in the graph; " +
                "1 jar(s) have a licence the policy does not accept" in failed,
            failed
        )
    }
}
