/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 *
 * Copyright (c) 2026 brain-lv (https://github.com/brain-lv)
 */

package lv.brain.gradle.patcher

internal data class HunkLine(val kind: Char, val text: String)

internal data class Hunk(
    val oldStart: Int,
    val oldCount: Int,
    val newStart: Int,
    val newCount: Int,
    val header: String,
    val lines: List<HunkLine>
) {
    val oldLines: List<String> get() = lines.filter { it.kind != '+' }.map { it.text }
    val newLines: List<String> get() = lines.filter { it.kind != '-' }.map { it.text }
}

internal data class FilePatch(
    val oldPath: String?,
    val newPath: String?,
    val hunks: List<Hunk>,
    val newEndsWithoutNewline: Boolean
) {
    val targetPath: String get() = oldPath ?: newPath ?: error("patch without a path")
    val isCreation: Boolean get() = oldPath == null
    val isDeletion: Boolean get() = newPath == null
}

internal class MalformedPatchException(message: String) : RuntimeException(message)

internal object UnifiedDiff {
    private val HUNK_HEADER = Regex("""^@@ -(\d+)(?:,(\d+))? \+(\d+)(?:,(\d+))? @@.*$""")

    fun parse(text: String): List<FilePatch> {
        val lines = text.split("\n").map { it.removeSuffix("\r") }
        val patches = ArrayList<FilePatch>()
        var i = 0
        while (i < lines.size) {
            val line = lines[i]
            if (line.startsWith("GIT binary patch") || line.startsWith("Binary files ")) {
                throw MalformedPatchException("line ${i + 1}: binary patches are not supported, only text changes")
            }
            if (line.startsWith("--- ") && i + 1 < lines.size && lines[i + 1].startsWith("+++ ")) {
                val oldPath = path(line.substring(4), "a/")
                val newPath = path(lines[i + 1].substring(4), "b/")
                if (oldPath == null && newPath == null) {
                    throw MalformedPatchException("line ${i + 1}: both paths are /dev/null")
                }
                i += 2
                val hunks = ArrayList<Hunk>()
                var newEndsWithoutNewline = false
                while (i < lines.size && lines[i].startsWith("@@")) {
                    val (hunk, next, noNewline) = parseHunk(lines, i)
                    hunks += hunk
                    newEndsWithoutNewline = noNewline
                    i = next
                }
                if (hunks.isEmpty()) {
                    throw MalformedPatchException("line ${i + 1}: '${line.take(80)}' is followed by no '@@' hunk")
                }
                patches += FilePatch(oldPath, newPath, hunks, newEndsWithoutNewline)
            } else {
                i++
            }
        }
        if (patches.isEmpty()) {
            throw MalformedPatchException("no '--- a/<file>' / '+++ b/<file>' / '@@' blocks found; is this a unified diff?")
        }
        return patches
    }

    private fun parseHunk(lines: List<String>, start: Int): Triple<Hunk, Int, Boolean> {
        val header = lines[start]
        val m = HUNK_HEADER.matchEntire(header)
            ?: throw MalformedPatchException("line ${start + 1}: malformed hunk header '$header'")
        val oldStart = m.groupValues[1].toInt()
        val oldCount = m.groupValues[2].ifEmpty { "1" }.toInt()
        val newStart = m.groupValues[3].toInt()
        val newCount = m.groupValues[4].ifEmpty { "1" }.toInt()

        val body = ArrayList<HunkLine>()
        var oldSeen = 0
        var newSeen = 0
        var i = start + 1
        var newEndsWithoutNewline = false
        while (oldSeen < oldCount || newSeen < newCount) {
            if (i >= lines.size) {
                throw MalformedPatchException(
                    "line ${start + 1}: hunk '$header' ends early: expected $oldCount old and $newCount new lines, " +
                        "found $oldSeen and $newSeen"
                )
            }
            val raw = lines[i]
            if (raw.startsWith("\\")) {
                i++
                continue
            }
            val kind = raw.firstOrNull() ?: ' '
            val text = if (raw.isEmpty()) "" else raw.substring(1)
            when (kind) {
                ' ' -> { oldSeen++; newSeen++ }
                '-' -> oldSeen++
                '+' -> newSeen++
                else -> throw MalformedPatchException(
                    "line ${i + 1}: unexpected line in hunk '$header' (a hunk line starts with ' ', '-' or '+'): '${raw.take(80)}'"
                )
            }
            body += HunkLine(kind, text)
            i++
        }
        while (i < lines.size && lines[i].startsWith("\\")) {
            val last = body.lastOrNull()
            if (last != null && last.kind != '-') newEndsWithoutNewline = true
            i++
        }
        return Triple(Hunk(oldStart, oldCount, newStart, newCount, header, body), i, newEndsWithoutNewline)
    }

    private fun path(raw: String, gitPrefix: String): String? {
        val value = raw.substringBefore('\t').trim()
        if (value == "/dev/null") return null
        return value.removePrefix(gitPrefix)
    }
}
