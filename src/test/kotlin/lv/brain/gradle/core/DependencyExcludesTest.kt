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

class DependencyExcludesTest {

    // ---- parsing ----

    @Test
    fun `a property value is split at commas, trimmed and de-duplicated`() {
        assertEquals(listOf("a.B", "c.d"), DependencyExcludes.parseClasses(" a.B , c.d,,a.B "))
        assertEquals(emptyList<String>(), DependencyExcludes.parseClasses(null))
        assertEquals(emptyList<String>(), DependencyExcludes.parseClasses("  , "))
    }

    @Test
    fun `class names are written with dots or slashes, with or without the class suffix`() {
        assertEquals("com.vendor.Foo", DependencyExcludes.normalizeClass("com/vendor/Foo.class"))
        assertEquals("com.vendor.Foo", DependencyExcludes.normalizeClass(" com.vendor.Foo "))
        assertEquals("com.vendor", DependencyExcludes.normalizeClass(".com..vendor."))
        assertEquals(null, DependencyExcludes.normalizeClass("  "))
    }

    @Test
    fun `jar paths are written with either slash and without a leading dot or slash`() {
        assertEquals("client/lib/old-", DependencyExcludes.normalizeJar("./client\\lib\\old-"))
        assertEquals("client/lib", DependencyExcludes.normalizeJar("/client/lib"))
        assertEquals(null, DependencyExcludes.normalizeJar(" "))
    }

    @Test
    fun `a comma inside braces belongs to the jar pattern`() {
        assertEquals(
            listOf("**/{old,legacy}-*.jar", "tools/"),
            DependencyExcludes.parseJars("**/{old,legacy}-*.jar, tools/")
        )
    }

    // ---- classes ----

    private fun classes(vararg patterns: String) = ClassExcludes(patterns.toList())

    @Test
    fun `a class name excludes that class, its inner classes and nothing else`() {
        val excludes = classes("com.vendor.Legacy")

        assertTrue(excludes.excludes("com/vendor/Legacy"))
        assertTrue(excludes.excludes("com/vendor/Legacy\$Inner"))
        assertTrue(excludes.excludes("com/vendor/Legacy\$Inner\$Deeper"))
        assertFalse(excludes.excludes("com/vendor/LegacyHelper"), "a name that only starts like the pattern")
        assertFalse(excludes.excludes("com/vendor/sub/Legacy"), "same simple name in another package")
    }

    @Test
    fun `a package excludes everything below it but not a sibling that starts the same way`() {
        val excludes = classes("com.vendor.shaded")

        assertTrue(excludes.excludes("com/vendor/shaded/Foo"))
        assertTrue(excludes.excludes("com/vendor/shaded/deep/er/Foo\$1"))
        assertFalse(excludes.excludes("com/vendor/shadedother/Foo"))
        assertFalse(excludes.excludes("com/vendor/Foo"))
    }

    @Test
    fun `star stays inside one package segment and double star crosses them`() {
        val star = classes("com.*.internal")
        assertTrue(star.excludes("com/a/internal/X"))
        assertTrue(star.excludes("com/b/internal/deep/X"))
        assertFalse(star.excludes("com/a/b/internal/X"), "one star is one segment")

        val doubleStar = classes("com.**.internal")
        assertTrue(doubleStar.excludes("com/a/b/internal/X"))
        assertTrue(doubleStar.excludes("com/a/internal/X"))

        val prefix = classes("com.vendor.Old*")
        assertTrue(prefix.excludes("com/vendor/OldApi"))
        assertTrue(prefix.excludes("com/vendor/OldApi\$Inner"))
        assertFalse(prefix.excludes("com/vendor/NewApi"))
        assertFalse(prefix.excludes("com/vendor/sub/OldApi"))

        val single = classes("com.vendor.V?")
        assertTrue(single.excludes("com/vendor/V1"))
        assertFalse(single.excludes("com/vendor/V10"))
    }

    @Test
    fun `characters that mean something in a regular expression are taken literally`() {
        val excludes = classes("com.vendor.Foo\$Bar", "com.vendor.a+b")
        assertTrue(excludes.excludes("com/vendor/Foo\$Bar"))
        assertTrue(excludes.excludes("com/vendor/a+b/X"))
        assertFalse(excludes.excludes("com/vendor/aab"))
    }

    @Test
    fun `no pattern excludes nothing`() {
        val excludes = classes()
        assertTrue(excludes.isEmpty)
        assertFalse(excludes.excludes("com/vendor/Foo"))
    }

    @Test
    fun `the patterns that matched nothing are reported`() {
        val excludes = classes("com.vendor.shaded", "com.vendro.Typo")
        excludes.excludes("com/vendor/shaded/Foo")
        excludes.excludes("com/vendor/Other")

        assertEquals(listOf("com.vendro.Typo"), excludes.unmatched())
    }

    // ---- jars ----

    private fun jars(vararg patterns: String, vendorDirName: String? = "vendor") = JarExcludes(patterns.toList(), vendorDirName)

    @Test
    fun `a plain pattern is a prefix of the path of the jar`() {
        val excludes = jars("client/lib/legacy-")

        assertTrue(excludes.excludes("client/lib/legacy-1.0.jar"))
        assertTrue(excludes.excludes("client/lib/legacy-tools-2.jar"))
        assertFalse(excludes.excludes("client/lib/modern-1.0.jar"))
        assertFalse(excludes.excludes("server/client/lib/legacy-1.0.jar"), "a prefix of the whole path, not of a segment")
    }

    @Test
    fun `a folder as a prefix excludes every jar below it`() {
        val excludes = jars("tools/")

        assertTrue(excludes.excludes("tools/a.jar"))
        assertTrue(excludes.excludes("tools/deep/er/b.jar"))
        assertFalse(excludes.excludes("toolkit/a.jar"))
    }

    @Test
    fun `wildcards make the pattern an Ant style pattern`() {
        val excludes = jars("**/legacy-*.jar", "server/lib/{old,ancient}-1.jar")

        assertTrue(excludes.excludes("client/lib/legacy-1.0.jar"))
        assertTrue(excludes.excludes("legacy-1.0.jar"))
        assertFalse(excludes.excludes("client/lib/modern-1.0.jar"))
        assertTrue(excludes.excludes("server/lib/old-1.jar"))
        assertTrue(excludes.excludes("server/lib/ancient-1.jar"))
        assertFalse(excludes.excludes("server/lib/other-1.jar"))
    }

    @Test
    fun `the name of the vendor folder in front of the path is accepted`() {
        val excludes = jars("vendor/client/lib/legacy-", "vendor/tools/**")

        assertTrue(excludes.excludes("client/lib/legacy-1.0.jar"))
        assertTrue(excludes.excludes("tools/a.jar"))
    }

    @Test
    fun `the jar patterns that matched nothing are reported`() {
        val excludes = jars("client/lib/legacy-", "serverr/")
        excludes.excludes("client/lib/legacy-1.0.jar")
        excludes.excludes("server/lib/a.jar")

        assertEquals(listOf("serverr/"), excludes.unmatched())
    }
}
