/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 *
 * Copyright (c) 2026 Edgars Grinvalds <edgars.grinvalds@brain.lv>
 */

package lv.brain.gradle.core

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

class VendorModuleLookupTest {

    @TempDir
    lateinit var tmp: File

    private val bundles = "lv.brain.vendor.bundle"

    private val modules = listOf(
        VendorModuleRef("lv.brain.vendor.client", "demo-client", "1.0.0"),
        VendorModuleRef("org.lite", "commons-lite", "3.8"),
        VendorModuleRef(bundles, "client", "2026.1.0")
    )

    private fun find(group: String?, name: String, list: List<VendorModuleRef> = modules) =
        VendorModuleLookup.find(list, group, name, bundles)

    private fun failure(block: () -> Unit): String =
        try { block(); error("expected a failure") } catch (e: IllegalArgumentException) { e.message.orEmpty() }

    @Test
    fun `a name that identifies one module gives the coordinates it is published under`() {
        assertEquals("lv.brain.vendor.client:demo-client:1.0.0", find(null, "demo-client"))
        assertEquals("org.lite:commons-lite:3.8", find("org.lite", "commons-lite"))
    }

    @Test
    fun `a bundle is not a module`() {
        assertTrue("no module 'client'" in failure { find(null, "client") })
    }

    @Test
    fun `an unknown module lists what exists`() {
        val message = failure { find("org.other", "commons-lite") }

        assertTrue("no module 'org.other:commons-lite'" in message, message)
        assertTrue("lv.brain.vendor.client:demo-client" in message && "org.lite:commons-lite" in message, message)
        assertTrue(bundles !in message.substringAfter("modules:"), message)
    }

    @Test
    fun `a name in several groups needs the group`() {
        val twice = modules + VendorModuleRef("lv.brain.vendor.server", "demo-client", "1.0.0")

        val message = failure { find(null, "demo-client", twice) }

        assertTrue("several groups" in message && "vendor.module(group, name)" in message, message)
        assertEquals("lv.brain.vendor.server:demo-client:1.0.0", find("lv.brain.vendor.server", "demo-client", twice))
    }

    @Test
    fun `a module in several versions is reported`() {
        val twice = modules + VendorModuleRef("org.lite", "commons-lite", "3.9")

        assertTrue("several versions (3.8, 3.9)" in failure { find("org.lite", "commons-lite", twice) })
    }

    @Test
    fun `the modules are read from the folders of the unpacked distribution`() {
        fun descriptor(group: String, name: String, version: String) =
            File(tmp, "ivy/$group/$name-$version/ivy-$version.xml").apply { parentFile.mkdirs() }.writeText(
                "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n<ivy-module version=\"2.0\">\n" +
                    "    <info organisation=\"$group\" module=\"$name\" revision=\"$version\" status=\"release\"/>\n</ivy-module>\n"
            )
        descriptor("org.lite", "commons-lite", "3.8")
        descriptor("lv.brain.vendor.client", "demo-client", "1.0.0-rc-1")
        File(tmp, "ivy/vendor-layout.properties").writeText("compileSplit=false\n")

        assertEquals(
            listOf(
                VendorModuleRef("lv.brain.vendor.client", "demo-client", "1.0.0-rc-1"),
                VendorModuleRef("org.lite", "commons-lite", "3.8")
            ),
            VendorModuleLookup.list(tmp)
        )
        assertEquals(emptyList<VendorModuleRef>(), VendorModuleLookup.list(File(tmp, "not-unpacked")))
    }
}
