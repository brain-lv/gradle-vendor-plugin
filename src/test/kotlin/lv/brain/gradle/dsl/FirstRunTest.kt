/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 *
 * Copyright (c) 2026 Edgars Grinvalds <edgars.grinvalds@brain.lv>
 */

package lv.brain.gradle.dsl

import lv.brain.gradle.support.GradleEnv
import lv.brain.gradle.support.SyntheticJars
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

/** A build without vendor.properties gets a sample to adjust, and fails until the sample marker is removed. */
@Tag("gradle-matrix")
class FirstRunTest {

    @TempDir
    lateinit var tmp: File

    private fun project(name: String, plugin: String): File {
        val dir = File(tmp, name).apply { mkdirs() }
        File(dir, "settings.gradle.kts").writeText("plugins { id(\"$plugin\") }\nrootProject.name = \"$name\"\n")
        File(dir, "build.gradle.kts").writeText("")
        return dir
    }

    private fun removeMarker(file: File) = file.writeText(file.readLines().filterNot { it.startsWith("vendor.sample=") }.joinToString("\n") + "\n")

    @Test
    fun `a distributor without vendor properties gets a sample that works once it is adjusted`() {
        val sdk = project("sdk", "lv.brain.vendor-distributor")
        SyntheticJars.write(File(sdk, "vendor/lib/acme-1.0.jar"), mapOf("com/acme/A.class" to SyntheticJars.clazz("com/acme/A")))

        val first = GradleEnv.buildAndFail(sdk, "packageVendorDistribution").output
        val properties = File(sdk, "vendor.properties")
        assertTrue("No vendor.properties was found" in first && "created a sample" in first, first)
        val text = properties.readText()
        assertTrue("vendor.sample=DELETE THIS LINE" in text && "DELETE THE NEXT LINE" in text && "vendor.dir=vendor" in text, text)
        val values = text.lines().filter { it.isNotBlank() && !it.startsWith("#") && !it.startsWith("vendor.sample") }
        values.forEach { line -> assertTrue(text.lines()[text.lines().indexOf(line) - 1].startsWith("#"), "no comment above $line") }

        val second = GradleEnv.buildAndFail(sdk, "packageVendorDistribution").output
        assertTrue("is still the generated sample" in second && "delete the line its first comment points to" in second, second)
        assertTrue("vendor.sample" !in second, second)

        properties.writeText(properties.readText().replace(Regex("vendor.sample=.*"), "vendor.sample=false"))
        val anyValue = GradleEnv.buildAndFail(sdk, "packageVendorDistribution").output
        assertTrue("is still the generated sample" in anyValue, anyValue)

        removeMarker(properties)
        GradleEnv.build(sdk, "packageVendorDistribution")
        assertTrue(File(sdk, "build/distributions/vendor-sdk-1.0.0.zip").isFile)
    }

    @Test
    fun `a consumer without vendor properties gets a sample, and a version from the command line needs none`() {
        val app = project("app", "lv.brain.vendor-consumer")

        val first = GradleEnv.buildAndFail(app, "help").output
        val properties = File(app, "vendor.properties")
        assertTrue("created a sample" in first && "its comments explain" in first, first)
        assertTrue(properties.readText().contains("vendor.url=") && properties.readText().contains("vendor.sample=DELETE THIS LINE"))

        File(app, "vendor.local.properties").writeText("vendor.localZip=missing.zip\n")
        properties.writeText(properties.readText().replace(Regex("vendor.sample=.*"), "   vendor.sample :"))
        val stillSample = GradleEnv.buildAndFail(app, "help").output
        assertTrue("still the generated sample" in stillSample, stillSample)

        properties.delete()
        File(app, "vendor.local.properties").delete()
        val fromCommandLine = GradleEnv.buildAndFail(app, "help", "-Pvendor.version=1.0.0").output
        assertTrue("generated sample" !in fromCommandLine && "must be configured" in fromCommandLine, fromCommandLine)
        assertTrue(!properties.exists())
    }
}
