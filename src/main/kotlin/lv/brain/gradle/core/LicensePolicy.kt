/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 *
 * Copyright (c) 2026 Edgars Grinvalds <edgars.grinvalds@brain.lv>
 */

package lv.brain.gradle.core

import java.io.Serializable

internal data class LicensePolicy(
    val allow: List<String> = emptyList(),
    val deny: List<String> = emptyList(),
    val unknown: String = "warn"
) : Serializable {
    val active: Boolean get() = allow.isNotEmpty() || deny.isNotEmpty()

    fun acceptable(license: String): Boolean {
        val names = setOf(license, normalize(license))
        val denied = deny.any { pattern -> names.any { matches(pattern, it) } }
        val allowed = allow.isEmpty() || allow.any { pattern -> names.any { matches(pattern, it) } }
        return allowed && !denied
    }

    fun accepts(licenses: List<String>): Boolean = licenses.any(::acceptable)

    companion object {
        private val RULES = listOf(
            Regex("lgpl.*2\\.1|lesser general public license.*2\\.1") to "LGPL-2.1",
            Regex("lgpl.*3|lesser general public license.*3") to "LGPL-3.0",
            Regex("agpl|affero") to "AGPL-3.0",
            Regex("gpl.*2.*(classpath|cpe)|general public license.*2.*classpath") to "GPL-2.0-with-classpath-exception",
            Regex("gpl.*3|general public license.*3") to "GPL-3.0",
            Regex("gpl.*2|general public license.*2") to "GPL-2.0",
            Regex("apache.*2|asl.*2|licenses/license-2\\.0") to "Apache-2.0",
            Regex("eclipse distribution license|\\bedl\\b|bsd.*3|3-clause|new bsd|revised bsd") to "BSD-3-Clause",
            Regex("bsd.*2|2-clause|simplified bsd|freebsd") to "BSD-2-Clause",
            Regex("epl.*2|eclipse public license.*2") to "EPL-2.0",
            Regex("epl.*1|eclipse public license.*1") to "EPL-1.0",
            Regex("mpl.*2|mozilla public license.*2") to "MPL-2.0",
            Regex("cddl.*1\\.1") to "CDDL-1.1",
            Regex("cddl|common development and distribution") to "CDDL-1.0",
            Regex("\\bmit\\b|licenses/mit") to "MIT",
            Regex("cc0|public domain") to "CC0-1.0",
            Regex("unlicense") to "Unlicense"
        )

        val SPDX_IDS: Set<String> = RULES.map { it.second }.toSet()

        fun normalize(license: String): String {
            val text = license.trim().lowercase()
            return RULES.firstOrNull { (regex, _) -> regex.containsMatchIn(text) }?.second ?: license.trim()
        }

        fun matches(pattern: String, license: String): Boolean {
            val regex = pattern.trim().split('*').joinToString(".*") { Regex.escape(it) }
            return Regex(regex, RegexOption.IGNORE_CASE).matches(license.trim())
        }
    }
}
