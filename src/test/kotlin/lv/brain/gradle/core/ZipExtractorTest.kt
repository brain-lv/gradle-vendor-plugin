/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 *
 * Copyright (c) 2026 Edgars Grinvalds <edgars.grinvalds@brain.lv>
 */

package lv.brain.gradle.core

import org.gradle.api.GradleException
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** Unpacking a distribution: only when its content changed, never outside the target, verified first when asked. */
class ZipExtractorTest {

    @TempDir
    lateinit var tmp: File

    private val target get() = File(tmp, "unpacked/2026.1.0")

    private fun zip(name: String, vararg entries: Pair<String, String>): File = File(tmp, name).apply {
        ZipOutputStream(outputStream()).use { out ->
            entries.forEach { (path, text) ->
                out.putNextEntry(ZipEntry(path))
                out.write(text.toByteArray())
                out.closeEntry()
            }
        }
    }

    @Test
    fun `a zip is unpacked once and again only when its content changes`() {
        val zip = zip("sdk.zip", "ivy/layout.txt" to "v2", "lib/a.txt" to "a")

        ZipExtractor.extractIfChanged(zip, target)
        assertEquals("a", File(target, "lib/a.txt").readText())
        assertTrue(ZipExtractor.isCurrent(zip, target))
        assertTrue(ZipExtractor.unpackedFingerprint(target).startsWith("content="))

        File(target, "lib/a.txt").writeText("touched")
        ZipExtractor.extractIfChanged(zip, target)
        assertEquals("touched", File(target, "lib/a.txt").readText(), "an unchanged zip is not unpacked again")

        zip("sdk.zip", "ivy/layout.txt" to "v2", "lib/b.txt" to "b")
        assertFalse(ZipExtractor.isCurrent(zip, target))
        ZipExtractor.extractIfChanged(zip, target)
        assertEquals("b", File(target, "lib/b.txt").readText())
        assertFalse(File(target, "lib/a.txt").exists(), "files of the previous content are gone")
    }

    @Test
    fun `an entry that points outside the target folder is refused and nothing is unpacked`() {
        val zip = zip("evil.zip", "ok.txt" to "fine", "../escaped.txt" to "bad")

        val failure = assertThrows<IllegalStateException> { ZipExtractor.extractIfChanged(zip, target) }

        assertTrue("Illegal ZIP entry outside target directory: ../escaped.txt" in failure.message!!, failure.message)
        assertFalse(File(target.parentFile, "escaped.txt").exists())
        assertFalse(target.exists())
    }

    @Test
    fun `the expected SHA-256 is checked before unpacking`() {
        val zip = zip("sdk.zip", "a.txt" to "a")

        val failure = assertThrows<GradleException> { ZipExtractor.extractIfChanged(zip, target, expectedSha256 = "0".repeat(64)) }
        assertTrue("vendor.sha256 expects ${"0".repeat(64)}" in failure.message!!, failure.message)
        assertFalse(target.exists())

        ZipExtractor.extractIfChanged(zip, target, expectedSha256 = ZipExtractor.sha256(zip))
        assertTrue(File(target, "a.txt").isFile)
        assertFalse(ZipExtractor.isCurrent(zip, target, expectedSha256 = "1".repeat(64)), "another expected hash verifies again")
    }
}
