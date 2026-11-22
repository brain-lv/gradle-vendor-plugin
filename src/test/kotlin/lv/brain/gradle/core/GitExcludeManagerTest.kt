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
import org.junit.jupiter.api.io.TempDir
import java.io.File

class GitExcludeManagerTest {

    @TempDir
    lateinit var tmp: File

    private fun entries(exclude: File) = exclude.readLines().filter { it.isNotBlank() && !it.startsWith("#") }

    @Test
    fun `project at the repository root gets root anchored entries`() {
        File(tmp, ".git").mkdirs()

        GitExcludeManager.ensureExcluded(tmp)

        assertEquals(listOf("/vendor.local.properties", "/.vendor/"), entries(File(tmp, ".git/info/exclude")))
        assertFalse(File(tmp, ".gitignore").exists())
    }

    @Test
    fun `project below the repository root gets entries with its path from the root`() {
        File(tmp, ".git").mkdirs()
        val project = File(tmp, "apps/consumer-project").apply { mkdirs() }

        GitExcludeManager.ensureExcluded(project)

        assertEquals(
            listOf("/apps/consumer-project/vendor.local.properties", "/apps/consumer-project/.vendor/"),
            entries(File(tmp, ".git/info/exclude"))
        )
        assertFalse(File(project, ".gitignore").exists())
    }

    @Test
    fun `running twice adds nothing and existing content is kept`() {
        val exclude = File(tmp, ".git/info/exclude").apply { parentFile.mkdirs(); writeText("*.log") }
        val project = File(tmp, "consumer-project").apply { mkdirs() }

        GitExcludeManager.ensureExcluded(project)
        val afterFirst = exclude.readText()
        GitExcludeManager.ensureExcluded(project)

        assertEquals(afterFirst, exclude.readText())
        assertEquals(listOf("*.log", "/consumer-project/vendor.local.properties", "/consumer-project/.vendor/"), entries(exclude))
    }

    @Test
    fun `an entry written without slashes is recognised`() {
        val exclude = File(tmp, ".git/info/exclude").apply { parentFile.mkdirs(); writeText("consumer-project/.vendor\n") }
        val project = File(tmp, "consumer-project").apply { mkdirs() }

        GitExcludeManager.ensureExcluded(project)

        assertEquals(listOf("consumer-project/.vendor", "/consumer-project/vendor.local.properties"), entries(exclude))
    }

    @Test
    fun `nothing happens outside a git repository`() {
        // a temp folder may still lie inside some repository, so only the folder itself is checked
        val project = File(tmp, "no-repo").apply { mkdirs() }
        if (GitExcludeManager.findWorkTree(project) != null) return

        GitExcludeManager.ensureExcluded(project)

        assertFalse(File(project, ".git").exists())
    }

    @Test
    fun `a linked worktree writes to the shared repository`() {
        val mainGit = File(tmp, "main/.git").apply { mkdirs() }
        val worktreeGitDir = File(mainGit, "worktrees/wt").apply { mkdirs() }
        File(worktreeGitDir, "commondir").writeText("../..\n")
        val worktree = File(tmp, "wt").apply { mkdirs() }
        File(worktree, ".git").writeText("gitdir: ${worktreeGitDir.absolutePath}\n")
        val project = File(worktree, "consumer-project").apply { mkdirs() }

        GitExcludeManager.ensureExcluded(project)

        val exclude = File(mainGit, "info/exclude")
        assertTrue(exclude.isFile)
        assertEquals(listOf("/consumer-project/vendor.local.properties", "/consumer-project/.vendor/"), entries(exclude))
    }

    @Test
    fun `special characters in folder names are escaped`() {
        File(tmp, ".git").mkdirs()
        val project = File(tmp, "app[1]").apply { mkdirs() }

        assertEquals(
            listOf("/app\\[1\\]/vendor.local.properties", "/app\\[1\\]/.vendor/"),
            GitExcludeManager.entriesFor(project, tmp)
        )
    }
}
