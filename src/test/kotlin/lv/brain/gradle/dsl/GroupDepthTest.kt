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
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.io.TempDir
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import java.io.File
import java.util.zip.ZipFile

/**
 * Vendor jars whose group has one, two or three segments resolve from the packaged distribution, with and without a
 * local Maven repository that the consumer also declares.
 */
@Tag("gradle-matrix")
class GroupDepthTest {

    @TempDir
    lateinit var tmp: File

    @ParameterizedTest(name = "group {0}, local maven repository present: {1}")
    @CsvSource("undefined,true", "undefined,false", "acme.tools,true", "acme.tools,false", "com.acme.tools,true", "com.acme.tools,false")
    fun `a jar resolves whatever the depth of its group`(group: String, m2Present: Boolean) {
        val sdk = File(tmp, "sdk")
        SyntheticJars.write(
            File(sdk, "vendor/lib/tool-1.0.jar"),
            mapOf("com/acme/Tool.class" to SyntheticJars.clazz("com/acme/Tool"), SyntheticJars.pomProperties(group, "tool", "1.0"))
        )
        File(sdk, "vendor.properties").writeText("vendor.version=2026.1.0\nvendor.category.lib=lib\n")
        File(sdk, "settings.gradle.kts").writeText("plugins { id(\"lv.brain.vendor-distributor\") }\nrootProject.name = \"vendor-sdk\"\n")
        File(sdk, "build.gradle.kts").writeText("")
        GradleEnv.build(sdk, "packageVendorDistribution")

        val zip = File(sdk, "build/distributions/vendor-sdk-2026.1.0.zip")
        ZipFile(zip).use { zf ->
            val module = zf.getEntry("ivy/$group/tool-1.0/tool-1.0.module")
            val text = zf.getInputStream(module).reader().readText()
            assertTrue("\"name\": \"tool-1.0.jar\", \"url\": \"../../../lib/tool-1.0.jar\"" in text, text)
        }

        val m2 = File(tmp, "m2/repository")
        if (m2Present) File(m2, "org/other/thing/1.0").mkdirs()
        val app = File(tmp, "app").apply { mkdirs() }
        File(app, "vendor.properties").writeText("vendor.version=2026.1.0\nvendor.localZip=../sdk/build/distributions/vendor-sdk-2026.1.0.zip\n")
        File(app, "settings.gradle.kts").writeText("plugins { id(\"lv.brain.vendor-consumer\") }\nrootProject.name = \"app\"\n")
        File(app, "build.gradle.kts").writeText(
            """
            plugins { java }
            repositories { mavenLocal() }
            dependencies {
                implementation(vendor.bundle("lib"))
                implementation(vendor.module(name = "tool"))
            }
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
        File(app, "src/main/java/app/App.java").apply { parentFile.mkdirs() }
            .writeText("package app;\npublic class App { com.acme.Tool tool; }\n")

        val output = GradleEnv.build(app, "compileJava", "printClasspaths", "-Dmaven.repo.local=${m2.absolutePath}").output

        assertEquals(listOf("tool-1.0.jar"), classpath(output, "COMPILE"), output)
        assertEquals(listOf("tool-1.0.jar"), classpath(output, "RUNTIME"), output)
    }
}
