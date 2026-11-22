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
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.util.zip.ZipFile

class VendorPatchEngineTest {

    @TempDir
    lateinit var tmp: File

    private val source get() = File(tmp, "vendor")
    private val patches get() = File(tmp, "internal-patches")
    private val work get() = File(tmp, "work")

    private fun file(path: String, text: String) = File(source, path).apply { parentFile.mkdirs(); writeText(text) }

    private fun step(name: String, text: String) = File(patches, name).apply { parentFile.mkdirs(); writeText(text.trimIndent() + "\n") }

    private fun run(options: PatchOptions = PatchOptions(), warnings: MutableList<String> = ArrayList()): PatchResult {
        val steps = VendorPatchEngine.discover(patches) { warnings += it }
        VendorPatchEngine.copySource(source, work)
        return VendorPatchEngine.applyAll(steps, work, "the folder vendor", options)
    }

    private fun failure(options: PatchOptions = PatchOptions()): String = assertThrows<PatchException> { run(options) }.message!!

    private val config = "name=demo\ntimeout=30\nretries=3\nmode=fast\n"

    private val timeoutTo60 = """
        --- a/conf/app.properties
        +++ b/conf/app.properties
        @@ -1,4 +1,4 @@
         name=demo
        -timeout=30
        +timeout=60
         retries=3
         mode=fast
    """

    @Test
    fun `steps are applied in number order to a copy and the source stays as it was`() {
        file("conf/app.properties", config)
        step("002-retries.patch", """
            --- a/conf/app.properties
            +++ b/conf/app.properties
            @@ -1,4 +1,4 @@
             name=demo
             timeout=60
            -retries=3
            +retries=5
             mode=fast
        """)
        step("001-timeout.patch", timeoutTo60)
        step("README.md", "not a step")

        val result = run()

        assertEquals(listOf("001-timeout.patch", "002-retries.patch"), result.applied)
        assertEquals("name=demo\ntimeout=60\nretries=5\nmode=fast\n", File(work, "conf/app.properties").readText())
        assertEquals(config, File(source, "conf/app.properties").readText())
    }

    @Test
    fun `a hunk that moved is applied where it is and the offset is noted`() {
        file("conf/app.properties", "# header\n# more\n$config")
        step("001-timeout.patch", timeoutTo60)

        val result = run()

        assertTrue(File(work, "conf/app.properties").readText().contains("timeout=60"))
        assertTrue(result.notes.single().contains("offset 2"), result.notes.toString())
    }

    @Test
    fun `windows line endings and a missing final newline are kept`() {
        File(source, "conf").mkdirs()
        File(source, "conf/app.properties").writeBytes("name=demo\r\ntimeout=30\r\nretries=3\r\nmode=fast".toByteArray())
        step("001-timeout.patch", """
            --- a/conf/app.properties
            +++ b/conf/app.properties
            @@ -1,4 +1,4 @@
             name=demo
            -timeout=30
            +timeout=60
             retries=3
             mode=fast
            \ No newline at end of file
        """)

        run()

        assertEquals("name=demo\r\ntimeout=60\r\nretries=3\r\nmode=fast", File(work, "conf/app.properties").readText())
    }

    @Test
    fun `files are created and deleted`() {
        file("conf/old.txt", "bye\n")
        step("001-files.patch", """
            --- /dev/null
            +++ b/conf/new.txt
            @@ -0,0 +1,2 @@
            +hello
            +world
            --- a/conf/old.txt
            +++ /dev/null
            @@ -1 +0,0 @@
            -bye
        """)

        run()

        assertEquals("hello\nworld\n", File(work, "conf/new.txt").readText())
        assertFalse(File(work, "conf/old.txt").exists())
    }

    @Test
    fun `a text file inside a jar is patched and the other entries stay`() {
        val clazz = SyntheticJars.clazz("com/demo/Api")
        SyntheticJars.write(File(source, "lib/app-1.0.jar"), mapOf("META-INF/app.properties" to config.toByteArray(), "com/demo/Api.class" to clazz))
        step("001-jar.patch", timeoutTo60.replace("conf/app.properties", "lib/app-1.0.jar!/META-INF/app.properties"))

        run()

        ZipFile(File(work, "lib/app-1.0.jar")).use { zip ->
            assertEquals("name=demo\ntimeout=60\nretries=3\nmode=fast\n", zip.getInputStream(zip.getEntry("META-INF/app.properties")).readBytes().decodeToString())
            assertTrue(zip.getInputStream(zip.getEntry("com/demo/Api.class")).readBytes().contentEquals(clazz))
        }
    }

    @Test
    fun `a signed jar is only changed when signatures may be removed`() {
        SyntheticJars.write(
            File(source, "lib/app-1.0.jar"),
            mapOf("META-INF/app.properties" to config.toByteArray(), "META-INF/VENDOR.SF" to "sig".toByteArray(), "META-INF/VENDOR.RSA" to "rsa".toByteArray())
        )
        step("001-jar.patch", timeoutTo60.replace("conf/app.properties", "lib/app-1.0.jar!/META-INF/app.properties"))

        val message = failure()
        assertTrue("This jar is signed" in message && "stripJarSignatures" in message, message)

        run(PatchOptions(stripJarSignatures = true))
        ZipFile(File(work, "lib/app-1.0.jar")).use { zip ->
            assertEquals(null, zip.getEntry("META-INF/VENDOR.SF"))
            assertEquals(null, zip.getEntry("META-INF/VENDOR.RSA"))
        }
    }

    @Test
    fun `a changed file names the step, the hunk, the closest match, the cause and the fix`() {
        file("conf/app.properties", "name=demo\ntimeout=45\nretries=3\nmode=fast\n")
        file("conf/other.properties", "x=1\n")
        step("001-other.patch", """
            --- a/conf/other.properties
            +++ b/conf/other.properties
            @@ -1 +1 @@
            -x=1
            +x=2
        """)
        step("002-timeout.patch", timeoutTo60)

        val message = failure()

        assertTrue("Vendor patch step 2 of 2 failed: 002-timeout.patch" in message, message)
        assertTrue("File: conf/app.properties" in message, message)
        assertTrue("hunk 1 of 1: @@ -1,4 +1,4 @@" in message, message)
        assertTrue("Closest match in the file, at line 1 (3 of 4 lines equal" in message, message)
        assertTrue("!     2 | timeout=45" in message, message)
        assertTrue("Likely cause: The file changed since the patch was made" in message, message)
        assertTrue("Applied before this one: 001-other.patch." in message, message)
        assertTrue("Nothing was written" in message, message)
    }

    @Test
    fun `a change that is already there is reported as already applied`() {
        file("conf/app.properties", "name=demo\ntimeout=60\nretries=3\nmode=fast\n")
        step("001-timeout.patch", timeoutTo60)

        val message = failure()

        assertTrue("already in the file, at line 1" in message && "delete this step" in message, message)
    }

    @Test
    fun `whitespace differences are reported and can be ignored`() {
        file("conf/app.properties", "name=demo\ntimeout=30   \nretries=3\nmode=fast\n")
        step("001-timeout.patch", timeoutTo60)

        val message = failure()
        assertTrue("spaces, tabs or line endings differ" in message && "ignoreWhitespace" in message, message)

        run(PatchOptions(ignoreWhitespace = true))
        assertTrue(File(work, "conf/app.properties").readText().contains("timeout=60"))
    }

    @Test
    fun `a missing file suggests where it moved and a path with a folder too many`() {
        file("config/app.properties", config)
        step("001-timeout.patch", timeoutTo60)
        assertTrue("A file with this name exists at: config/app.properties" in failure())

        work.deleteRecursively()
        step("001-timeout.patch", timeoutTo60.replace("conf/app.properties", "vendor/config/app.properties"))
        assertTrue("one leading folder too many ('vendor/')" in failure())
    }

    @Test
    fun `a jar that was renamed in a vendor upgrade is pointed out`() {
        SyntheticJars.write(File(source, "lib/app-2.0.jar"), mapOf("META-INF/app.properties" to config.toByteArray()))
        step("001-jar.patch", timeoutTo60.replace("conf/app.properties", "lib/app-1.0.jar!/META-INF/app.properties"))

        val message = failure()

        assertTrue("The jar 'lib/app-1.0.jar' does not exist" in message, message)
        assertTrue("another version?): lib/app-2.0.jar" in message, message)
    }

    @Test
    fun `binary files and invalid patch files are explained`() {
        File(source, "lib").mkdirs()
        File(source, "lib/data.bin").writeBytes(byteArrayOf(1, 0, 2, 0))
        step("001-bin.patch", timeoutTo60.replace("conf/app.properties", "lib/data.bin"))
        assertTrue("binary file" in failure())

        step("001-bin.patch", "this is not a diff")
        assertTrue("not a valid unified diff" in failure())
    }

    @Test
    fun `duplicate numbers and unsupported step types stop before anything runs, gaps only warn`() {
        file("conf/app.properties", config)
        step("001-a.patch", timeoutTo60)
        step("001-b.patch", timeoutTo60)
        step("002-setup.gradle.kts", "println(1)")
        val message = assertThrows<PatchException> { VendorPatchEngine.discover(patches) }.message!!
        assertTrue("Steps share the number 1: 001-a.patch, 001-b.patch" in message, message)
        assertTrue("Gradle script steps are not supported, because" in message, message)

        patches.deleteRecursively()
        step("001-a.patch", timeoutTo60)
        file("conf/other.properties", "x=1\n")
        step("003-c.patch", """
            --- a/conf/other.properties
            +++ b/conf/other.properties
            @@ -1 +1 @@
            -x=1
            +x=2
        """)
        val warnings = ArrayList<String>()
        run(warnings = warnings)
        assertTrue(warnings.single().contains("skip number(s) 2"), warnings.toString())
    }
}
