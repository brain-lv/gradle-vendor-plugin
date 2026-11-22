/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 *
 * Copyright (c) 2026 Edgars Grinvalds <edgars.grinvalds@brain.lv>
 */

package lv.brain.gradle.core

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.ObjectInputStream
import java.io.ObjectOutputStream

class CategoryRuleTest {

    private fun matches(pattern: String, path: String, vendorDirName: String? = "vendor"): Boolean =
        CategoryRule("c", listOf(pattern)).matches(path, vendorDirName)

    @Test
    fun `directory glob selects everything below it and nothing else`() {
        assertTrue(matches("client/lib/**", "client/lib/example-json-2.11.0.jar"))
        assertTrue(matches("client/lib/**", "client/lib/nested/deeper/a.jar"))
        assertFalse(matches("client/lib/**", "server/lib/example-json-2.11.0.jar"))
        assertFalse(matches("client/lib/**", "client/other/example-json-2.11.0.jar"))
        assertFalse(matches("client/lib/**", "client/library/a.jar"))
    }

    @Test
    fun `double star matches zero or more directories`() {
        // none of these matched with java.nio glob, so every jar silently fell into the default category
        assertTrue(matches("**/client/**", "client/lib/example-json-2.11.0.jar"))
        assertTrue(matches("**/client/**", "a/b/client/lib/example-json-2.11.0.jar"))
        assertTrue(matches("client/**/*.jar", "client/example-json-2.11.0.jar"))
        assertTrue(matches("client/**/*.jar", "client/lib/example-json-2.11.0.jar"))
        assertTrue(matches("**/lib/*.jar", "lib/example-json-2.11.0.jar"))
        assertTrue(matches("**/lib/*.jar", "client/lib/example-json-2.11.0.jar"))
        assertTrue(matches("**", "anything/at/all.jar"))
        assertTrue(matches("**/*.jar", "top.jar"))
    }

    @Test
    fun `single star and question mark stay inside one path segment`() {
        assertTrue(matches("client/*.jar", "client/example-json-2.11.0.jar"))
        assertFalse(matches("client/*.jar", "client/lib/example-json-2.11.0.jar"))
        assertTrue(matches("client/example-json-?.jar", "client/example-json-2.jar"))
        assertFalse(matches("client/example-json-?.jar", "client/example-json-22.jar"))
        assertTrue(matches("client/example-json-*", "client/example-json-2.11.0.jar"))
    }

    @Test
    fun `a directory without wildcards or a trailing slash selects everything below it`() {
        assertTrue(matches("client/lib", "client/lib/example-json-2.11.0.jar"))
        assertTrue(matches("client/lib/", "client/lib/example-json-2.11.0.jar"))
        assertTrue(matches("client", "client/lib/nested/a.jar"))
        assertFalse(matches("client/lib", "client/library/a.jar"))
        // an exact jar name still means exactly that file
        assertTrue(matches("client/lib/example-json-2.11.0.jar", "client/lib/example-json-2.11.0.jar"))
        assertFalse(matches("client/lib/example-json-2.11.0.jar", "client/lib/example-json-2.12.0.jar"))
    }

    @Test
    fun `leading dot slash, slash and the vendor directory name are ignored`() {
        assertTrue(matches("./client/lib/**", "client/lib/a.jar"))
        assertTrue(matches("/client/lib/**", "client/lib/a.jar"))
        assertTrue(matches("vendor/client/lib/**", "client/lib/a.jar"))
        assertTrue(matches("vendor/client", "client/lib/a.jar"))
        assertFalse(matches("vendor/client/lib/**", "client/lib/a.jar", vendorDirName = "third-party"))
    }

    @Test
    fun `braces and character classes are supported`() {
        assertTrue(matches("client/{lib,ext}/*.jar", "client/lib/a.jar"))
        assertTrue(matches("client/{lib,ext}/*.jar", "client/ext/a.jar"))
        assertFalse(matches("client/{lib,ext}/*.jar", "client/other/a.jar"))
        assertTrue(matches("client/lib/[ab]-1.jar", "client/lib/a-1.jar"))
        assertFalse(matches("client/lib/[ab]-1.jar", "client/lib/c-1.jar"))
    }

    @Test
    fun `windows separators are normalised and dots are not wildcards`() {
        assertTrue(matches("client\\lib\\**", "client/lib/a.jar"))
        assertTrue(matches("client/lib/**", "client\\lib\\a.jar"))
        assertFalse(matches("client/lib/a.jar", "client/lib/aXjar"))
    }

    @Test
    fun `a rule with several patterns matches when any of them matches`() {
        val rule = CategoryRule("server", listOf("server/x1/**", "server/x2/**"))
        assertTrue(rule.matches("server/x1/a.jar", "vendor"))
        assertTrue(rule.matches("server/x2/a.jar", "vendor"))
        assertFalse(rule.matches("server/x3/a.jar", "vendor"))
    }

    @Test
    fun `rule is serializable and still matches afterwards`() {
        val rule = CategoryRule("client", listOf("**/client/**"))
        assertTrue(rule.matches("client/a.jar", "vendor")) // fills the transient cache before serializing

        val bytes = ByteArrayOutputStream().also { ObjectOutputStream(it).use { out -> out.writeObject(rule) } }.toByteArray()
        val copy = ObjectInputStream(ByteArrayInputStream(bytes)).use { it.readObject() } as CategoryRule

        assertEquals(rule, copy)
        assertTrue(copy.matches("a/client/b.jar", "vendor"))
        assertFalse(copy.matches("server/b.jar", "vendor"))
    }
}
