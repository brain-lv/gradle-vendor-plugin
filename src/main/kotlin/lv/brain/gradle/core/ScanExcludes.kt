/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 *
 * Copyright (c) 2026 Edgars Grinvalds <edgars.grinvalds@brain.lv>
 */

package lv.brain.gradle.core

internal class ScanExcludes(patterns: List<String>, vendorDirName: String?) {
    private val regexes: List<Regex> = patterns.flatMap { PathPatterns.compile(it, vendorDirName) }

    fun excludesDir(relativeUnixPath: String): Boolean {
        val name = relativeUnixPath.substringAfterLast('/')
        return name.startsWith(".") || relativeUnixPath in FIXED_TOP_LEVEL_DIRS || matches(relativeUnixPath)
    }

    fun excludesFile(relativeUnixPath: String): Boolean =
        relativeUnixPath.substringAfterLast('/').startsWith(".") ||
            FIXED_TOP_LEVEL_DIRS.any { relativeUnixPath.startsWith("$it/") } ||
            relativeUnixPath.split('/').dropLast(1).any { it.startsWith(".") } ||
            matches(relativeUnixPath)

    private fun matches(path: String): Boolean = regexes.any { it.matches(path) }

    companion object {
        val FIXED_TOP_LEVEL_DIRS = listOf(VendorRepositoryLayout.IVY_DIR, "build")

        fun gradlePatterns(patterns: List<String>, vendorDirName: String?): List<String> {
            val result = linkedSetOf<String>()
            FIXED_TOP_LEVEL_DIRS.forEach { result += "$it/**" }
            result += "**/.*/**"
            result += "**/.*"
            result += expand(patterns, vendorDirName)
            return result.toList()
        }

        fun expand(patterns: List<String>, vendorDirName: String?): List<String> {
            val result = linkedSetOf<String>()
            for (raw in patterns) {
                val base = PathPatterns.normalize(raw)
                if (base.isEmpty()) continue
                val variants = linkedSetOf(base)
                if (!vendorDirName.isNullOrEmpty() && base.startsWith("$vendorDirName/")) variants += base.removePrefix("$vendorDirName/")
                for (variant in variants.filter { it.isNotEmpty() }) {
                    result += variant
                    if (!variant.endsWith("/**")) result += "$variant/**"
                }
            }
            return result.toList()
        }

        fun zipPatterns(patterns: List<String>, vendorDirName: String?): List<String> =
            expand(patterns.map { if (it.trim() == "*") "**" else it }, vendorDirName)

        fun parse(raw: String?): List<String> = DependencyExcludes.parseList(raw).map { PathPatterns.normalize(it) }.filter { it.isNotEmpty() }
    }
}
