/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 *
 * Copyright (c) 2026 brain-lv (https://github.com/brain-lv)
 */

package lv.brain.gradle.distributor

import org.gradle.testkit.runner.GradleRunner
import org.gradle.testkit.runner.TaskOutcome
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

// A bare include("<vendor pattern>") in settings.gradle.kts is Gradle's project include, not a vendor category include.
// The plugin must turn that into one clear message instead of Gradle's "project without an existing directory" error.
// The scripts reference no plugin types, so this works under any Gradle/TestKit classpath handling.
class VendorDistributorSettingsGuardTest {

    @TempDir
    lateinit var projectDir: File

    private fun settings(body: String) {
        File(projectDir, ".git").mkdirs()
        File(projectDir, "vendor.properties").writeText("vendor.version=2026.1.0\n")
        File(projectDir, "build.gradle.kts").writeText("")
        File(projectDir, "settings.gradle.kts").writeText(
            """
            plugins {
                id("lv.brain.vendor-distributor")
            }
            rootProject.name = "vendor-sdk-distributor"
            $body
            """.trimIndent()
        )
    }

    private fun runner(vararg args: String) = GradleRunner.create()
        .withProjectDir(projectDir)
        .withPluginClasspath()
        .withArguments(*args, "--stacktrace")

    @Test
    fun `a vendor pattern passed to the settings level include fails with an explanation`() {
        settings("""include("client/X/**")""")

        val output = runner("help").buildAndFail().output

        assertTrue(output.contains("outside a category"), output)
        assertTrue(output.contains("':client/X/**'"), output)
        assertTrue(output.contains("vendor.category.<name>=client/X/**"), output)
        assertTrue(output.contains("Groovy DSL"), output)
    }

    @Test
    fun `real subprojects are left alone`() {
        File(projectDir, "app").mkdirs()
        settings("""include("app")""")

        val result = runner("help").build()

        assertEquals(TaskOutcome.SUCCESS, result.task(":help")?.outcome)
    }
}
