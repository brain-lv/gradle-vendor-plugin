/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 *
 * Copyright (c) 2026 brain-lv (https://github.com/brain-lv)
 */

package lv.brain.gradle.dsl

import lv.brain.gradle.support.Dep
import lv.brain.gradle.support.DistributorSettings
import lv.brain.gradle.support.Dsl
import lv.brain.gradle.support.Workspace
import lv.brain.gradle.support.classpath
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

/**
 * The same configuration written in Groovy and in Kotlin has to give the same result, byte for byte where that is
 * meaningful. This catches the differences that no single-language test sees: an option that only one language can set,
 * or a default that differs between the two.
 */
@Tag("gradle-matrix")
class DslEquivalenceTest {

    @TempDir
    lateinit var tmp: File

    private val settings = DistributorSettings(
        slimJars = true,
        compilePackages = listOf("com.demo"),
        defaultCategory = "misc",
        categories = mapOf("client" to listOf("client/lib/**"), "server" to listOf("server/lib"))
    )

    private val groovy by lazy { Workspace(Dsl.GROOVY, File(tmp, "groovy")) }
    private val kotlin by lazy { Workspace(Dsl.KOTLIN, File(tmp, "kotlin")) }

    @Test
    fun `groovy and kotlin settings produce the same distribution`() {
        for (ws in listOf(groovy, kotlin)) {
            ws.createSdk(settings)
            ws.gradle(ws.sdkDir, "packageVendorDistribution")
        }

        val fromGroovy = groovy.distribution()
        val fromKotlin = kotlin.distribution()

        assertEquals(fromGroovy.keys.sorted(), fromKotlin.keys.sorted(), "the two distributions contain different files")
        for (name in fromGroovy.keys.sorted().filterNot { it.endsWith(".jar") }) {
            assertEquals(String(fromGroovy.getValue(name)), String(fromKotlin.getValue(name)), "content of $name differs")
        }
        // jars: the same entries (a jar file as a whole carries timestamps)
        for (name in fromGroovy.keys.sorted().filter { it.endsWith(".jar") }) {
            assertEquals(
                zipNames(fromGroovy.getValue(name)), zipNames(fromKotlin.getValue(name)),
                "entries of $name differ"
            )
        }
    }

    @Test
    fun `groovy and kotlin build scripts resolve the same classpaths`() {
        for (ws in listOf(groovy, kotlin)) {
            ws.createSdk(settings)
            ws.gradle(ws.sdkDir, "generateVendorIvyRepo")
            ws.createApp(listOf(Dep.Bundle("server"), Dep.Module("commons-lite", group = "org.lite")))
        }

        val fromGroovy = groovy.gradle(groovy.appDir, "printClasspaths").output
        val fromKotlin = kotlin.gradle(kotlin.appDir, "printClasspaths").output

        assertEquals(classpath(fromGroovy, "COMPILE"), classpath(fromKotlin, "COMPILE"), "compile classpath")
        assertEquals(classpath(fromGroovy, "RUNTIME"), classpath(fromKotlin, "RUNTIME"), "runtime classpath")
    }

    private fun zipNames(jar: ByteArray): List<String> =
        java.util.zip.ZipInputStream(jar.inputStream()).use { zin ->
            generateSequence { zin.nextEntry }.map { it.name }.sorted().toList()
        }
}
