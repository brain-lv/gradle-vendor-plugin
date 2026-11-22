/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 *
 * Copyright (c) 2026 Edgars Grinvalds <edgars.grinvalds@brain.lv>
 */

package lv.brain.gradle.distributor

import java.util.TreeMap

internal object ApiReferenceScanner {
    data class ApiReference(val fromClass: String, val toClass: String)

    fun scan(
        raw: RawReferences,
        owners: Map<String, String>,
        selfModule: String,
        skipClass: (String) -> Boolean = { false }
    ): Map<String, ApiReference> {
        val found = TreeMap<String, ApiReference>()
        for ((fromClass, targets) in raw.api) {
            if (skipClass(fromClass)) continue
            for (toClass in targets) {
                val owner = owners[toClass] ?: continue
                if (owner == selfModule) continue
                val candidate = ApiReference(fromClass, toClass)
                val existing = found[owner]
                if (existing == null || compareValuesBy(candidate, existing, { it.fromClass }, { it.toClass }) < 0) {
                    found[owner] = candidate
                }
            }
        }
        return found
    }
}
