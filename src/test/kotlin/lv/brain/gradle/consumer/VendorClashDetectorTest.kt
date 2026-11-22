/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 *
 * Copyright (c) 2026 Edgars Grinvalds <edgars.grinvalds@brain.lv>
 */

package lv.brain.gradle.consumer

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

// Same group:artifact at another version is a clash; everything else is Gradle's normal conflict resolution.
class VendorClashDetectorTest {

    private val lib = "org.lite" to "commons-lite"

    private fun vendor(version: String, selected: String = version, graph: String = "runtime") = DependencyRequest(
        requester = "lv.brain.vendor.bundle:client:2026.1.0", fromVendor = true, fromProject = false,
        group = lib.first, module = lib.second, requested = version, selected = selected, graph = graph
    )

    private fun project(version: String, selected: String? = version, graph: String = "runtime") = DependencyRequest(
        requester = "project :app", fromVendor = false, fromProject = true,
        group = lib.first, module = lib.second, requested = version, selected = selected, graph = graph
    )

    private fun thirdParty(version: String, selected: String? = version) = DependencyRequest(
        requester = "org.foo:foo:1.0", fromVendor = false, fromProject = false,
        group = lib.first, module = lib.second, requested = version, selected = selected, graph = "runtime"
    )

    @Test
    fun `a project dependency on another version than the vendor jar is a clash`() {
        val clashes = VendorClashDetector.find(listOf(vendor("3.8", selected = "3.12"), project("3.12")))

        assertEquals(1, clashes.size)
        assertEquals("org.lite:commons-lite", clashes.single().coordinate)
        assertEquals(setOf("3.8"), clashes.single().vendorVersions)
        assertEquals("3.12", clashes.single().runtimeVersion)
        assertEquals(listOf("project :app"), clashes.single().consumerRequests.map { it.requester })
    }

    @Test
    fun `the same version is no clash`() {
        assertTrue(VendorClashDetector.find(listOf(vendor("3.8"), project("3.8"))).isEmpty())
    }

    @Test
    fun `a project compileOnly dependency on a lower version is a clash even though the vendor version wins at runtime`() {
        // compileOnly is only in the compile graph: the project compiles against 3.5 and runs with the vendor's 3.8
        val clashes = VendorClashDetector.find(listOf(vendor("3.8"), project("3.5", graph = "compile")))

        assertEquals(1, clashes.size)
        assertEquals("3.8", clashes.single().runtimeVersion)
    }

    @Test
    fun `a third party library that asks for a lower version is no clash because the vendor version wins`() {
        assertTrue(VendorClashDetector.find(listOf(vendor("3.8"), thirdParty("3.5", selected = "3.8"))).isEmpty())
    }

    @Test
    fun `a third party library that replaces the vendor version is a clash`() {
        val clashes = VendorClashDetector.find(listOf(vendor("3.8", selected = "3.12"), thirdParty("3.12")))

        assertEquals(1, clashes.size)
        assertEquals(listOf("org.foo:foo:1.0"), clashes.single().consumerRequests.map { it.requester })
    }

    @Test
    fun `no clash without a request from the project side or without a vendor request`() {
        // two vendor jars disagreeing with each other is the distribution's business
        assertTrue(VendorClashDetector.find(listOf(vendor("3.8", selected = "3.9"), vendor("3.9"))).isEmpty())
        assertTrue(VendorClashDetector.find(listOf(project("3.12"))).isEmpty())
    }

    @Test
    fun `a dynamic version counts as whatever it resolved to`() {
        assertTrue(VendorClashDetector.find(listOf(vendor("3.8"), project("3.+", selected = "3.8"))).isEmpty())
        assertEquals(1, VendorClashDetector.find(listOf(vendor("3.8", selected = "3.14"), project("3.+", selected = "3.14"))).size)
    }

    @Test
    fun `ignored coordinates are skipped`() {
        val requests = listOf(vendor("3.8", selected = "3.12"), project("3.12"))

        assertTrue(VendorClashDetector.find(requests, VendorClashDetector.ignoreRegexes(listOf("org.lite:*"))).isEmpty())
        assertTrue(VendorClashDetector.find(requests, VendorClashDetector.ignoreRegexes(listOf("org.lite:commons-lite"))).isEmpty())
        assertEquals(1, VendorClashDetector.find(requests, VendorClashDetector.ignoreRegexes(listOf("org.other:*", "org.lite:other"))).size)
    }

    @Test
    fun `the message names the library the versions and the way out`() {
        val clashes = VendorClashDetector.find(listOf(vendor("3.8", selected = "3.12"), project("3.12")))

        val message = VendorClashDetector.message("project ':app' (source set 'main')", clashes)

        assertTrue("org.lite:commons-lite" in message, message)
        assertTrue("3.8" in message && "3.12" in message, message)
        assertTrue("project :app asks for 3.12" in message, message)
        assertTrue("vendor.clashIgnore" in message && "vendor.clashCheck=false" in message, message)
    }

    @Test
    fun `a vendor jar named only by its file is reported next to a library with the same artifact name`() {
        val guava = DependencyRequest(
            requester = "project :app", fromVendor = false, fromProject = true,
            group = "com.google.guava", module = "guava", requested = "31.1-jre", selected = "31.1-jre", graph = "runtime"
        )
        val identities = listOf("lv.brain.vendor.core:guava:30.0", "lv.brain.vendor.core:other:1.0")

        val found = VendorClashDetector.possibleDuplicates(listOf(guava, guava.copy(graph = "compile")), identities)

        assertEquals(listOf(PossibleDuplicate("lv.brain.vendor.core:guava:30.0", "com.google.guava:guava", "31.1-jre", "project :app")), found)
        assertTrue(VendorClashDetector.possibleDuplicates(listOf(guava), identities, VendorClashDetector.ignoreRegexes(listOf("com.google.guava:*"))).isEmpty())
        assertTrue(VendorClashDetector.possibleDuplicates(listOf(guava.copy(group = "lv.brain.vendor.core")), identities).isEmpty())
        val message = VendorClashDetector.duplicateMessage("project ':app'", found)
        assertTrue("vendor lv.brain.vendor.core:guava:30.0" in message && "com.google.guava:guava:31.1-jre" in message, message)
        assertTrue("vendor.rule.<n>" in message, message)
    }
}
