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

/** Hand-made dependency fixes, the pom check, and the warning about vendor jars that are only named by their file. */
@Tag("gradle-matrix")
class DependencyOverridesTest {

    @TempDir
    lateinit var tmp: File

    private val sdk get() = File(tmp, "sdk")

    private fun createSdk(properties: String, settingsBlock: String = "") {
        SyntheticJars.write(File(sdk, "vendor/lib/core-1.0.jar"), mapOf("com/acme/Core.class" to SyntheticJars.clazz("com/acme/Core", bodyUses = "org/lite/Lite")))
        SyntheticJars.write(File(sdk, "vendor/lib/commons-lite-3.8.jar"), mapOf("org/lite/Lite.class" to SyntheticJars.clazz("org/lite/Lite")))
        SyntheticJars.write(File(sdk, "vendor/lib/extra-1.0.jar"), mapOf("com/acme/Extra.class" to SyntheticJars.clazz("com/acme/Extra")))
        File(sdk, "vendor.properties").writeText("vendor.version=2026.1.0\n$properties")
        File(sdk, "settings.gradle.kts").writeText("plugins { id(\"lv.brain.vendor-distributor\") }\nrootProject.name = \"vendor-sdk\"\n$settingsBlock")
        File(sdk, "build.gradle.kts").writeText("")
    }

    @Suppress("UNCHECKED_CAST")
    private fun dependsOn(module: String): List<String> {
        val json = JsonSlurper().parse(File(sdk, "build/vendor/module-dependencies.json")) as Map<String, Any?>
        val entry = (json["modules"] as List<Map<String, Any?>>).single { it["moduleId"] == module }
        return (entry["dependsOn"] as List<Map<String, Any?>>).map { it["moduleId"] as String }
    }

    @Test
    fun `vendor properties and the settings block add and remove dependencies`() {
        createSdk(
            "vendor.dependencyAdd=core->extra\n",
            "vendorDistributor { dependencyRemove.add(\"core->commons-lite\") }\n"
        )

        GradleEnv.build(sdk, "resolveVendorModules")

        assertEquals(listOf("extra"), dependsOn("core"))
    }

    @Test
    fun `a library of the project with the name of a vendor jar named by its file is reported`() {
        createSdk("")
        GradleEnv.build(sdk, "packageVendorDistribution")

        val pom = File(tmp, "repo/org/lite/commons-lite/3.12/commons-lite-3.12.pom").apply { parentFile.mkdirs() }
        pom.writeText(
            "<project><modelVersion>4.0.0</modelVersion><groupId>org.lite</groupId><artifactId>commons-lite</artifactId><version>3.12</version></project>\n"
        )
        SyntheticJars.write(File(pom.parentFile, "commons-lite-3.12.jar"), mapOf("org/lite/Lite.class" to SyntheticJars.clazz("org/lite/Lite")))

        val app = File(tmp, "app").apply { mkdirs() }
        File(app, "vendor.properties").writeText("vendor.version=2026.1.0\nvendor.localZip=../sdk/build/distributions/vendor-sdk-2026.1.0.zip\n")
        File(app, "settings.gradle.kts").writeText("plugins { id(\"lv.brain.vendor-consumer\") }\nrootProject.name = \"app\"\n")
        File(app, "build.gradle.kts").writeText(
            """
            plugins { java }
            repositories { maven { url = uri("../repo") } }
            dependencies {
                implementation(vendor.bundle("core"))
                implementation("org.lite:commons-lite:3.12")
            }
            """.trimIndent() + "\n"
        )

        val output = GradleEnv.build(app, "verifyVendorClasspath").output

        assertTrue("Vendor jars named only by their file may be the same library" in output, output)
        assertTrue("vendor lv.brain.vendor.core:commons-lite:3.8  and  org.lite:commons-lite:3.12" in output, output)
    }
}
