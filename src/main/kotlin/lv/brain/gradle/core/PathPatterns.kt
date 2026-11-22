/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 *
 * Copyright (c) 2026 Edgars Grinvalds <edgars.grinvalds@brain.lv>
 */

package lv.brain.gradle.core

internal object PathPatterns {
    fun normalize(raw: String): String {
        var p = raw.trim().replace('\\', '/')
        while (p.startsWith("./")) p = p.removePrefix("./")
        p = p.trimStart('/')
        p = p.replace(Regex("/{2,}"), "/")
        if (p.isNotEmpty() && p.endsWith("/")) p += "**"
        return p
    }

    fun compile(raw: String, vendorDirName: String?): List<Regex> {
        val base = normalize(raw)
        if (base.isEmpty()) return emptyList()

        val variants = linkedSetOf(base)
        if (!vendorDirName.isNullOrEmpty() && base.startsWith("$vendorDirName/")) {
            variants += base.removePrefix("$vendorDirName/")
        }

        val expanded = linkedSetOf<String>()
        for (variant in variants) {
            if (variant.isEmpty()) continue
            expanded += variant
            val lastSegment = variant.substringAfterLast('/')
            val namesDirectory = lastSegment.none { it in WILDCARD_CHARS } &&
                !lastSegment.endsWith(".jar", ignoreCase = true)
            if (namesDirectory) expanded += "$variant/**"
        }
        return expanded.map { toRegex(it) }
    }

    private const val WILDCARD_CHARS = "*?[{"

    private fun toRegex(pattern: String): Regex {
        val segments = pattern.split('/')
        val sb = StringBuilder()
        segments.forEachIndexed { index, segment ->
            val isLast = index == segments.lastIndex
            if (segment == "**") {
                sb.append(if (isLast) ".*" else "(?:[^/]+/)*")
            } else {
                sb.append(segmentToRegex(segment))
                if (!isLast) sb.append('/')
            }
        }
        return Regex(sb.toString())
    }

    private fun segmentToRegex(segment: String): String {
        val sb = StringBuilder()
        var i = 0
        while (i < segment.length) {
            i = when (val c = segment[i]) {
                '*' -> star(segment, i, sb)
                '?' -> i.also { sb.append("[^/]") }
                '[' -> characterClass(segment, i, sb)
                '{' -> alternatives(segment, i, sb)
                else -> i.also { sb.append(escape(c)) }
            } + 1
        }
        return sb.toString()
    }

    private fun star(segment: String, start: Int, sb: StringBuilder): Int {
        var i = start
        while (i + 1 < segment.length && segment[i + 1] == '*') i++
        sb.append("[^/]*")
        return i
    }

    private fun characterClass(segment: String, start: Int, sb: StringBuilder): Int {
        val close = segment.indexOf(']', startIndex = start + 2)
        if (close == -1) {
            sb.append("\\[")
            return start
        }
        val body = segment.substring(start + 1, close).let { if (it.startsWith("!")) "^" + it.substring(1) else it }
        sb.append('[').append(body.replace("\\", "\\\\")).append(']')
        return close
    }

    private fun alternatives(segment: String, start: Int, sb: StringBuilder): Int {
        val close = matchingBrace(segment, start)
        if (close == -1) {
            sb.append("\\{")
            return start
        }
        val options = splitTopLevel(segment.substring(start + 1, close))
        sb.append("(?:").append(options.joinToString("|") { segmentToRegex(it) }).append(')')
        return close
    }

    private fun matchingBrace(text: String, open: Int): Int {
        var depth = 0
        for (idx in open until text.length) {
            when (text[idx]) {
                '{' -> depth++
                '}' -> if (--depth == 0) return idx
            }
        }
        return -1
    }

    private fun splitTopLevel(text: String): List<String> {
        val parts = mutableListOf<String>()
        var depth = 0
        var start = 0
        text.forEachIndexed { idx, ch ->
            when (ch) {
                '{' -> depth++
                '}' -> depth--
                ',' -> if (depth == 0) {
                    parts += text.substring(start, idx)
                    start = idx + 1
                }
            }
        }
        parts += text.substring(start)
        return parts
    }

    private fun escape(c: Char): String =
        if (c in "\\^$.|+()[]{}*?") "\\$c" else c.toString()
}
