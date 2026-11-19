/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 *
 * Copyright (c) 2026 brain-lv (https://github.com/brain-lv)
 */

package lv.brain.gradle.patcher

internal class TextContent(
    val lines: MutableList<String>,
    var endsWithNewline: Boolean,
    private val crlf: Boolean
) {
    fun toBytes(): ByteArray {
        val body = lines.joinToString("\n") + if (endsWithNewline && lines.isNotEmpty()) "\n" else ""
        val text = if (crlf) body.replace("\n", "\r\n") else body
        return text.toByteArray(Charsets.ISO_8859_1)
    }

    companion object {
        fun of(bytes: ByteArray): TextContent {
            val raw = String(bytes, Charsets.ISO_8859_1)
            val crlf = raw.contains("\r\n")
            val text = raw.replace("\r\n", "\n")
            if (text.isEmpty()) return TextContent(ArrayList(), endsWithNewline = true, crlf = crlf)
            val endsWithNewline = text.endsWith("\n")
            val lines = (if (endsWithNewline) text.dropLast(1) else text).split("\n").toMutableList()
            return TextContent(lines, endsWithNewline, crlf)
        }

        fun isBinary(bytes: ByteArray): Boolean = bytes.take(8000).any { it == 0.toByte() }
    }
}

internal enum class HunkCause { ALREADY_APPLIED, WHITESPACE, CHANGED, NOT_FOUND }

internal class HunkFailure(
    val hunkIndex: Int,
    val hunk: Hunk,
    val reason: HunkCause,
    val expectedAtLine: Int,
    val foundAtLine: Int?,
    val actual: List<String>
) : RuntimeException("hunk ${hunkIndex + 1} failed: $reason")

internal object HunkApplier {
    private val WHITESPACE = Regex("\\s+")

    fun apply(content: TextContent, hunks: List<Hunk>, newEndsWithoutNewline: Boolean, ignoreWhitespace: Boolean): List<String> {
        val notes = ArrayList<String>()
        val lines = content.lines
        var offset = 0
        var touchedEnd = false
        hunks.forEachIndexed { index, hunk ->
            val old = hunk.oldLines
            val new = hunk.newLines
            val original = if (hunk.oldCount == 0) hunk.oldStart else hunk.oldStart - 1
            val expected = (original + offset).coerceIn(0, lines.size)
            val pos = find(lines, old, expected, ::exact)
                ?: (if (ignoreWhitespace) find(lines, old, expected, ::sameIgnoringWhitespace) else null)
                ?: throw diagnose(index, hunk, lines, expected, ignoreWhitespace)
            if (pos != expected) notes += "hunk ${index + 1} applied at line ${pos + 1} (expected ${expected + 1}, offset ${pos - expected})"
            repeat(old.size) { lines.removeAt(pos) }
            lines.addAll(pos, new)
            if (pos + new.size == lines.size) touchedEnd = true
            offset = (pos - original) + (new.size - old.size)
        }
        if (touchedEnd) content.endsWithNewline = !newEndsWithoutNewline
        return notes
    }

    private fun diagnose(index: Int, hunk: Hunk, lines: List<String>, expected: Int, ignoreWhitespace: Boolean): HunkFailure {
        val old = hunk.oldLines
        val new = hunk.newLines
        val changes = hunk.lines.any { it.kind != ' ' }
        if (changes && new.isNotEmpty()) {
            find(lines, new, expected, ::exact)?.let {
                return HunkFailure(index, hunk, HunkCause.ALREADY_APPLIED, expected, it, slice(lines, it, new.size))
            }
        }
        if (!ignoreWhitespace) {
            find(lines, old, expected, ::sameIgnoringWhitespace)?.let {
                return HunkFailure(index, hunk, HunkCause.WHITESPACE, expected, it, slice(lines, it, old.size))
            }
        }
        val best = bestPartialMatch(lines, old, expected)
        return if (best == null) {
            HunkFailure(index, hunk, HunkCause.NOT_FOUND, expected, null, slice(lines, expected, old.size))
        } else {
            HunkFailure(index, hunk, HunkCause.CHANGED, expected, best, slice(lines, best, old.size))
        }
    }

    private fun find(lines: List<String>, block: List<String>, expected: Int, same: (String, String) -> Boolean): Int? {
        if (block.isEmpty()) return expected.coerceIn(0, lines.size)
        val last = lines.size - block.size
        if (last < 0) return null
        for (distance in 0..lines.size) {
            for (candidate in intArrayOf(expected - distance, expected + distance)) {
                if (candidate in 0..last && matches(lines, block, candidate, same)) return candidate
            }
            if (expected - distance < 0 && expected + distance > last) return null
        }
        return null
    }

    private fun matches(lines: List<String>, block: List<String>, at: Int, same: (String, String) -> Boolean): Boolean =
        block.indices.all { same(lines[at + it], block[it]) }

    private fun bestPartialMatch(lines: List<String>, block: List<String>, expected: Int): Int? {
        if (block.isEmpty() || lines.isEmpty()) return null
        var best: Int? = null
        var bestScore = 0
        for (candidate in 0..maxOf(0, lines.size - block.size)) {
            val score = block.indices.count { candidate + it < lines.size && lines[candidate + it] == block[it] }
            val better = score > bestScore ||
                (score == bestScore && score > 0 && best != null && kotlin.math.abs(candidate - expected) < kotlin.math.abs(best - expected))
            if (better) {
                best = candidate
                bestScore = score
            }
        }
        return best
    }

    private fun slice(lines: List<String>, from: Int, size: Int): List<String> {
        val start = from.coerceIn(0, lines.size)
        return lines.subList(start, minOf(lines.size, start + maxOf(size, 1)))
    }

    private fun exact(a: String, b: String): Boolean = a == b

    private fun sameIgnoringWhitespace(a: String, b: String): Boolean =
        a.replace(WHITESPACE, " ").trim() == b.replace(WHITESPACE, " ").trim()
}
