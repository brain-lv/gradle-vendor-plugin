/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 *
 * Copyright (c) 2026 Edgars Grinvalds <edgars.grinvalds@brain.lv>
 */

package lv.brain.gradle.patcher

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir
import java.io.File

/** Reading and writing unified diffs, and turning workspace edits into steps. */
class DiffFormatTest {

    @TempDir
    lateinit var tmp: File

    private fun failure(text: String): String = assertThrows<MalformedPatchException> { UnifiedDiff.parse(text) }.message!!

    @Test
    fun `a patch with several files, git headers and a missing final newline is read completely`() {
        val patches = UnifiedDiff.parse(
            """
            diff --git a/a.txt b/a.txt
            index 1..2 100644
            --- a/a.txt
            +++ b/a.txt
            @@ -1 +1 @@
            -old
            +new
            \ No newline at end of file
            --- /dev/null
            +++ b/created.txt
            @@ -0,0 +1,2 @@
            +one
            +two
            """.trimIndent()
        )

        assertEquals(2, patches.size)
        assertEquals("a.txt", patches[0].targetPath)
        assertTrue(patches[0].newEndsWithoutNewline)
        assertTrue(patches[1].isCreation)
        assertEquals(listOf("one", "two"), patches[1].hunks.single().newLines)
    }

    @Test
    fun `broken patches are explained`() {
        assertTrue("binary patches are not supported" in failure("GIT binary patch\nliteral 1\n"))
        assertTrue("both paths are /dev/null" in failure("--- /dev/null\n+++ /dev/null\n@@ -0,0 +1 @@\n+x\n"))
        assertTrue("is followed by no '@@' hunk" in failure("--- a/x\n+++ b/x\nno hunk\n"))
        assertTrue("is this a unified diff?" in failure("just text\n"))
        assertTrue("malformed hunk header" in failure("--- a/x\n+++ b/x\n@@ nonsense @@\n"))
        assertTrue("ends early" in failure("--- a/x\n+++ b/x\n@@ -1,3 +1,3 @@\n a\n"))
    }

    @Test
    fun `generated sections create, delete, and change only the final newline`() {
        val created = TextDiff.section(null, "new.txt", null, "a\nb\n".toByteArray())!!
        assertTrue(created.startsWith("--- /dev/null\n+++ b/new.txt\n@@ -0,0 +1,2 @@\n+a\n+b\n"), created)

        val deleted = TextDiff.section("old.txt", null, "a\n".toByteArray(), null)!!
        assertTrue(deleted.startsWith("--- a/old.txt\n+++ /dev/null\n@@ -1 +0,0 @@\n-a\n"), deleted)

        val newline = TextDiff.section("f.txt", "f.txt", "a\nb".toByteArray(), "a\nb\n".toByteArray())!!
        assertTrue("-b\n\\ No newline at end of file\n+b\n" in newline, newline)
        assertTrue(TextDiff.reproduces(newline, "a\nb".toByteArray(), "a\nb\n".toByteArray()))

        assertNull(TextDiff.section("same.txt", "same.txt", "x\n".toByteArray(), "x\n".toByteArray()))
        assertTrue(!TextDiff.reproduces("not a diff", null, "x".toByteArray()))
    }

    @Test
    fun `a file replaced by a folder and a folder replaced by a file become a delete and an add`() {
        val baseline = File(tmp, "baseline").apply { mkdirs() }
        val workspace = File(tmp, "workspace").apply { mkdirs() }
        File(baseline, "conf").writeText("was a file\n")
        File(baseline, "data/part.txt").apply { parentFile.mkdirs() }.writeText("was in a folder\n")
        File(workspace, "conf/app.properties").apply { parentFile.mkdirs() }.writeText("now a folder\n")
        File(workspace, "data").writeText("now a file\n")

        val changes = PatchWorkspace.compare(baseline, workspace)

        assertEquals(listOf("conf", "data/"), changes.deleted)
        assertEquals(listOf("added conf/app.properties", "added data"), changes.described)
        val patches = File(tmp, "patches")
        PatchWorkspace.nextSteps(patches, "swap", changes)
        val work = File(tmp, "work")
        VendorPatchEngine.copySource(baseline, work)
        VendorPatchEngine.applyAll(VendorPatchEngine.discover(patches), work, "the folder", PatchOptions())
        assertTrue(PatchWorkspace.compare(work, workspace).isEmpty)
    }
}
