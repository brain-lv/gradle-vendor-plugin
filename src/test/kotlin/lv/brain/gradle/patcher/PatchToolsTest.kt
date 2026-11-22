/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 *
 * Copyright (c) 2026 Edgars Grinvalds <edgars.grinvalds@brain.lv>
 */

package lv.brain.gradle.patcher

import lv.brain.gradle.support.SyntheticJars
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir
import org.objectweb.asm.Opcodes
import java.io.File
import java.util.zip.ZipFile
import kotlin.random.Random

/** Folder and delete steps, the generated diffs, and the workspace that turns edits into steps. */
class PatchToolsTest {

    @TempDir
    lateinit var tmp: File

    private val source get() = File(tmp, "vendor")
    private val patches get() = File(tmp, "internal-patches")
    private val work get() = File(tmp, "work")

    private fun file(root: File, path: String, text: String) = File(root, path).apply { parentFile.mkdirs(); writeText(text) }

    private fun run(options: PatchOptions = PatchOptions()): PatchResult {
        val steps = VendorPatchEngine.discover(patches)
        VendorPatchEngine.copySource(source, work)
        return VendorPatchEngine.applyAll(steps, work, "the folder vendor", options)
    }

    private fun entry(jar: File, name: String): ByteArray? = ZipFile(jar).use { zip -> zip.getEntry(name)?.let { e -> zip.getInputStream(e).use { it.readBytes() } } }

    @Test
    fun `a folder step replaces a class inside a jar and adds plain files`() {
        SyntheticJars.write(File(source, "lib/app-1.0.jar"), mapOf("com/acme/Login.class" to SyntheticJars.clazz("com/acme/Login", classVersion = Opcodes.V11)))
        val fixed = SyntheticJars.clazz("com/acme/Login", bodyUses = "com/acme/Fixed", classVersion = Opcodes.V11)
        File(patches, "001-fix-login/lib/app-1.0.jar/com/acme/Login.class").apply { parentFile.mkdirs(); writeBytes(fixed) }
        File(patches, "001-fix-login/conf/extra.bin").apply { parentFile.mkdirs(); writeBytes(byteArrayOf(0, 1, 2)) }

        val result = run()

        assertTrue(entry(File(work, "lib/app-1.0.jar"), "com/acme/Login.class")!!.contentEquals(fixed))
        assertTrue(File(work, "conf/extra.bin").readBytes().contentEquals(byteArrayOf(0, 1, 2)))
        assertTrue(result.notes.single().endsWith("1 file(s) replaced, 1 added"), result.notes.toString())
        assertTrue(result.warnings.isEmpty(), result.warnings.toString())
    }

    @Test
    fun `a class at the wrong path fails and a class for a newer Java warns`() {
        SyntheticJars.write(File(source, "lib/app-1.0.jar"), mapOf("com/acme/Login.class" to SyntheticJars.clazz("com/acme/Login", classVersion = Opcodes.V11)))
        val step = File(patches, "001-fix/lib/app-1.0.jar/com/acme/Login.class").apply { parentFile.mkdirs() }

        step.writeBytes(SyntheticJars.clazz("com/acme/Other"))
        val wrong = assertThrows<PatchException> { run() }.message!!
        assertTrue("holds com.acme.Other, but its path stands for com.acme.Login" in wrong, wrong)

        step.writeBytes(SyntheticJars.clazz("com/acme/Login", classVersion = Opcodes.V17))
        val warning = run().warnings.single()
        assertTrue("needs Java 17, but the other classes of the jar run on Java 11" in warning, warning)
    }

    @Test
    fun `a delete step removes files, folders and jar entries, and names a path that is not there`() {
        file(source, "docs/a.txt", "a")
        file(source, "docs/old/b.txt", "b")
        SyntheticJars.write(File(source, "lib/app-1.0.jar"), mapOf("a/A.class" to SyntheticJars.clazz("a/A"), "META-INF/old.xml" to "<x/>".toByteArray()))
        file(patches, "001-cleanup.delete", "# outdated\ndocs/old/\ndocs/a.txt\nlib/app-1.0.jar!/META-INF/old.xml\n")

        run()

        assertFalse(File(work, "docs/old").exists())
        assertFalse(File(work, "docs/a.txt").exists())
        assertNull(entry(File(work, "lib/app-1.0.jar"), "META-INF/old.xml"))
        assertNotNull(entry(File(work, "lib/app-1.0.jar"), "a/A.class"))

        file(patches, "001-cleanup.delete", "docs/missing.txt\n")
        assertTrue("The file does not exist in the vendor folder" in assertThrows<PatchException> { run() }.message!!)
    }

    @Test
    fun `a failing step writes nothing of itself`() {
        file(source, "conf/a.properties", "a=1\n")
        file(source, "conf/b.properties", "b=1\n")
        file(patches, "001-two.patch", """
            --- a/conf/a.properties
            +++ b/conf/a.properties
            @@ -1 +1 @@
            -a=1
            +a=2
            --- a/conf/b.properties
            +++ b/conf/b.properties
            @@ -1 +1 @@
            -b=9
            +b=2
        """.trimIndent() + "\n")
        VendorPatchEngine.copySource(source, work)
        val statuses = VendorPatchEngine.status(VendorPatchEngine.discover(patches), work, PatchOptions())

        assertEquals(StepOutcome.FAILED, statuses.single().outcome)
        assertEquals("a=1\n", File(work, "conf/a.properties").readText())
    }

    @Test
    fun `generated diffs reproduce the new text exactly`() {
        val random = Random(42)
        repeat(300) { round ->
            val old = List(random.nextInt(0, 30)) { "line ${random.nextInt(0, 8)}" }
            val new = old.toMutableList()
            repeat(random.nextInt(1, 6)) {
                when (random.nextInt(3)) {
                    0 -> if (new.isNotEmpty()) new.removeAt(random.nextInt(new.size))
                    1 -> new.add(random.nextInt(new.size + 1), "new ${random.nextInt(100)}")
                    else -> if (new.isNotEmpty()) new[random.nextInt(new.size)] = "changed ${random.nextInt(100)}"
                }
            }
            fun bytes(lines: List<String>, newline: Boolean) = (lines.joinToString("\n") + if (newline && lines.isNotEmpty()) "\n" else "").toByteArray()
            val before = bytes(old, random.nextBoolean())
            val after = bytes(new, random.nextBoolean())
            val section = TextDiff.section("f.txt", "f.txt", before, after)
            if (before.contentEquals(after)) {
                assertNull(section, "round $round")
            } else if (section != null) {
                assertTrue(TextDiff.reproduces(section, before, after), "round $round:\n$section")
            }
        }
    }

    @Test
    fun `edits in the workspace become steps that reproduce it`() {
        file(source, "conf/app.properties", "name=demo\ntimeout=30\nretries=3\n")
        file(source, "docs/old.txt", "old\n")
        SyntheticJars.write(File(source, "lib/app-1.0.jar"), mapOf(
            "META-INF/app.xml" to "<app>\n  <timeout>30</timeout>\n</app>\n".toByteArray(),
            "com/acme/Login.class" to SyntheticJars.clazz("com/acme/Login")
        ))
        val baseline = File(tmp, "baseline")
        val workspace = File(tmp, "workspace")
        VendorPatchEngine.copySource(source, baseline)
        baseline.copyRecursively(workspace)

        file(workspace, "conf/app.properties", "name=demo\ntimeout=60\nretries=3\n")
        File(workspace, "docs/old.txt").delete()
        file(workspace, "docs/new.txt", "new\n")
        SyntheticJars.write(File(workspace, "lib/app-1.0.jar"), mapOf(
            "META-INF/app.xml" to "<app>\n  <timeout>60</timeout>\n</app>\n".toByteArray(),
            "com/acme/Login.class" to SyntheticJars.clazz("com/acme/Login", bodyUses = "com/acme/Fixed")
        ))

        val changes = PatchWorkspace.compare(baseline, workspace)
        val written = PatchWorkspace.nextSteps(patches, "fixes", changes)

        assertEquals(listOf("001-fixes.delete", "002-fixes.patch", "003-fixes"), written.map { it.name })
        assertTrue(File(patches, "003-fixes/lib/app-1.0.jar/com/acme/Login.class").isFile)
        run()
        assertTrue(PatchWorkspace.compare(work, workspace).isEmpty, PatchWorkspace.compare(work, workspace).described.toString())
    }

    @Test
    fun `refresh rewrites a step that moved and leaves exact ones alone`() {
        file(source, "conf/app.properties", "# added by the vendor\n# in a new version\nname=demo\ntimeout=30\nretries=3\n")
        file(source, "conf/other.properties", "x=1\n")
        val moved = file(patches, "001-timeout.patch", """
            Longer timeout for slow networks.
            --- a/conf/app.properties
            +++ b/conf/app.properties
            @@ -1,3 +1,3 @@
             name=demo
            -timeout=30
            +timeout=60
             retries=3
        """.trimIndent() + "\n")
        val exactText = "--- a/conf/other.properties\n+++ b/conf/other.properties\n@@ -1 +1 @@\n-x=1\n+x=2\n"
        val exact = file(patches, "002-other.patch", exactText)
        VendorPatchEngine.copySource(source, work)

        val result = PatchWorkspace.refresh(VendorPatchEngine.discover(patches), work, PatchOptions(), "the folder vendor")

        assertNull(result.failure)
        assertEquals(listOf("001-timeout.patch"), result.refreshed)
        assertEquals(exactText, exact.readText())
        val refreshed = moved.readText()
        assertTrue(refreshed.startsWith("Longer timeout for slow networks.\n--- a/conf/app.properties"), refreshed)
        assertTrue("@@ -1,5 +1,5 @@" in refreshed, refreshed)
        assertTrue(run().notes.isEmpty(), "the refreshed step applies without offset")
    }
}
