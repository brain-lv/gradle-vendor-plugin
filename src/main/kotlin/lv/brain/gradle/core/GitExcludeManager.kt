/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 *
 * Copyright (c) 2026 brain-lv (https://github.com/brain-lv)
 */

package lv.brain.gradle.core

import org.gradle.api.logging.Logging
import java.io.File
import java.io.IOException

object GitExcludeManager {
    private val logger = Logging.getLogger(GitExcludeManager::class.java)

    internal val LOCAL_FILES = listOf("vendor.local.properties", ".vendor/")
    private const val HEADER = "# Added automatically by lv.brain.gradle vendor plugin"

    fun ensureExcluded(projectDir: File) {
        val project = projectDir.canonicalFile
        val workTree = findWorkTree(project) ?: return
        val gitDir = resolveGitDir(File(workTree, ".git")) ?: return
        val excludeFile = File(gitDir, "info/exclude")

        val wanted = entriesFor(project, workTree)
        try {
            val existing = if (excludeFile.isFile) excludeFile.readLines().map(::normalize).toSet() else emptySet()
            val missing = wanted.filter { normalize(it) !in existing }
            if (missing.isEmpty()) return

            excludeFile.parentFile.mkdirs()
            val needsLeadingNewline = excludeFile.isFile && excludeFile.length() > 0 && !excludeFile.readText().endsWith("\n")
            excludeFile.appendText(
                buildString {
                    if (needsLeadingNewline) appendLine()
                    appendLine(HEADER)
                    missing.forEach { appendLine(it) }
                }
            )
        } catch (e: IOException) {
            logger.warn("lv.brain.gradle: could not update ${excludeFile.path}: ${e.message}")
        }
    }

    internal fun entriesFor(projectDir: File, workTree: File): List<String> {
        val rel = projectDir.canonicalFile.relativeTo(workTree.canonicalFile).invariantSeparatorsPath.trim('/')
        val prefix = if (rel.isEmpty()) "/" else "/" + rel.split('/').joinToString("/") { escape(it) } + "/"
        return LOCAL_FILES.map { prefix + it }
    }

    internal fun findWorkTree(start: File): File? {
        var current: File? = start.canonicalFile
        while (current != null) {
            if (File(current, ".git").exists()) return current
            current = current.parentFile
        }
        return null
    }

    internal fun resolveGitDir(dotGit: File): File? {
        if (dotGit.isDirectory) return dotGit
        if (!dotGit.isFile) return null
        val target = dotGit.readLines().firstOrNull { it.startsWith("gitdir:") }
            ?.removePrefix("gitdir:")?.trim()?.takeIf { it.isNotEmpty() }
            ?: return null
        val gitDir = File(target).let { if (it.isAbsolute) it else File(dotGit.parentFile, target) }.canonicalFile
        val commonDir = File(gitDir, "commondir").takeIf { it.isFile }?.readText()?.trim()?.takeIf { it.isNotEmpty() }
            ?: return gitDir
        return File(commonDir).let { if (it.isAbsolute) it else File(gitDir, commonDir) }.canonicalFile
    }

    private fun normalize(line: String): String = line.trim().trim('/')

    private fun escape(path: String): String =
        path.replace(Regex("""([\\*?\[\]!#])"""), """\\$1""").replace(Regex("^ | $")) { "\\" + it.value }
}
