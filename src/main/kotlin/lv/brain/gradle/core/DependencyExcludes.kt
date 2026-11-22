/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 *
 * Copyright (c) 2026 Edgars Grinvalds <edgars.grinvalds@brain.lv>
 */

package lv.brain.gradle.core

object DependencyExcludes {
    fun parseList(raw: String?): List<String> {
        if (raw == null) return emptyList()
        val parts = ArrayList<String>()
        val current = StringBuilder()
        var depth = 0
        for (c in raw) {
            when {
                c == '{' -> { depth++; current.append(c) }
                c == '}' -> { if (depth > 0) depth--; current.append(c) }
                c == ',' && depth == 0 -> { parts += current.toString(); current.setLength(0) }
                else -> current.append(c)
            }
        }
        parts += current.toString()
        return parts.map { it.trim() }.filter { it.isNotEmpty() }.distinct()
    }

    fun normalizeClass(raw: String): String? {
        var p = raw.trim().replace('/', '.').removeSuffix(".class").trim('.')
        while (p.contains("..")) p = p.replace("..", ".")
        return p.ifEmpty { null }
    }

    fun normalizeJar(raw: String): String? {
        var p = raw.trim().replace('\\', '/')
        while (p.startsWith("./")) p = p.removePrefix("./")
        p = p.trimStart('/')
        return p.ifEmpty { null }
    }

    fun parseClasses(raw: String?): List<String> = parseList(raw).mapNotNull { normalizeClass(it) }.distinct()

    fun parseJars(raw: String?): List<String> = parseList(raw).mapNotNull { normalizeJar(it) }.distinct()
}

internal class ClassExcludes(patterns: Collection<String>) {
    private val patterns: List<String> = patterns.mapNotNull { DependencyExcludes.normalizeClass(it) }.distinct()
    private val regexes: List<Regex> = this.patterns.map { toRegex(it) }
    private val matched = BooleanArray(this.patterns.size)
    private val decisions = HashMap<String, Boolean>()

    val isEmpty: Boolean get() = patterns.isEmpty()

    fun excludes(internalName: String): Boolean {
        if (patterns.isEmpty()) return false
        return decisions.getOrPut(internalName) { decide(internalName) }
    }

    fun unmatched(): List<String> = patterns.filterIndexed { index, _ -> !matched[index] }

    private fun decide(internalName: String): Boolean {
        val name = internalName.replace('/', '.')
        val candidates = ArrayList<String>()
        candidates += name
        val simple = name.substringAfterLast('.')
        if (simple.contains('$')) candidates += name.removeSuffix(simple) + simple.substringBefore('$')
        var dot = name.indexOf('.')
        while (dot >= 0) {
            candidates += name.substring(0, dot)
            dot = name.indexOf('.', dot + 1)
        }
        var excluded = false
        regexes.forEachIndexed { index, regex ->
            if (candidates.any { regex.matches(it) }) {
                matched[index] = true
                excluded = true
            }
        }
        return excluded
    }

    private fun toRegex(pattern: String): Regex {
        val sb = StringBuilder()
        var i = 0
        while (i < pattern.length) {
            val c = pattern[i]
            when {
                c == '*' && i + 1 < pattern.length && pattern[i + 1] == '*' -> { sb.append(".*"); i++ }
                c == '*' -> sb.append("[^.]*")
                c == '?' -> sb.append("[^.]")
                c.isLetterOrDigit() || c == '_' -> sb.append(c)
                else -> sb.append('\\').append(c)
            }
            i++
        }
        return Regex(sb.toString())
    }
}

internal class JarExcludes(patterns: Collection<String>, vendorDirName: String?) {
    private class Entry(val pattern: String, val globs: List<Regex>, val prefixes: List<String>) {
        var matched = false
        fun matches(path: String): Boolean = prefixes.any { path.startsWith(it) } || globs.any { it.matches(path) }
    }

    private val entries: List<Entry> = patterns.mapNotNull { DependencyExcludes.normalizeJar(it) }.distinct().map { pattern ->
        val hasWildcard = pattern.any { it in "*?[{" }
        val prefixes = if (hasWildcard) emptyList() else listOfNotNull(
            pattern,
            if (!vendorDirName.isNullOrEmpty() && pattern.startsWith("$vendorDirName/")) pattern.removePrefix("$vendorDirName/") else null
        )
        Entry(pattern, PathPatterns.compile(pattern, vendorDirName), prefixes)
    }

    val isEmpty: Boolean get() = entries.isEmpty()

    fun excludes(relativeUnixPath: String): Boolean {
        var excluded = false
        for (entry in entries) {
            if (entry.matches(relativeUnixPath)) {
                entry.matched = true
                excluded = true
            }
        }
        return excluded
    }

    fun unmatched(): List<String> = entries.filter { !it.matched }.map { it.pattern }
}

internal object DependencyOverrides {
    fun parse(raw: String?, key: String): List<String> = DependencyExcludes.parseList(raw).map { normalize(it, key) }.distinct()

    fun normalize(entry: String, key: String): String {
        val parts = entry.split("->")
        val from = parts.getOrNull(0)?.trim().orEmpty()
        val to = parts.getOrNull(1)?.trim().orEmpty()
        require(parts.size == 2 && from.isNotEmpty() && to.isNotEmpty() && from != to) {
            "Invalid $key entry '$entry'. Use <module>-><module> with the module names of module-dependencies.json, " +
                "for example app-core->commons-lite."
        }
        return "$from->$to"
    }

    fun split(entry: String): Pair<String, String> = entry.substringBefore("->") to entry.substringAfter("->")
}
