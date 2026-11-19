/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 *
 * Copyright (c) 2026 brain-lv (https://github.com/brain-lv)
 */

package lv.brain.gradle.core

import java.io.File

internal data class VendorModuleRef(val group: String, val artifact: String, val version: String) {
    val notation: String get() = "$group:$artifact:$version"
}

internal object VendorModuleLookup {
    fun list(vendorRoot: File): List<VendorModuleRef> {
        fun dirs(parent: File): List<File> = parent.listFiles { f -> f.isDirectory }?.sortedBy { it.name }.orEmpty()
        val result = ArrayList<VendorModuleRef>()
        for (group in dirs(File(vendorRoot, VendorRepositoryLayout.IVY_DIR))) {
            for (artifact in dirs(group)) {
                for (version in dirs(artifact)) result.add(VendorModuleRef(group.name, artifact.name, version.name))
            }
        }
        return result
    }

    fun find(modules: List<VendorModuleRef>, group: String?, name: String, bundleGroup: String): String {
        val candidates = modules.filter { it.artifact == name && it.group != bundleGroup && (group == null || it.group == group) }

        if (candidates.isEmpty()) {
            val wanted = if (group == null) "'$name'" else "'$group:$name'"
            val known = modules.filter { it.group != bundleGroup }.map { "${it.group}:${it.artifact}" }.distinct()
            val list = if (known.isEmpty()) "the vendor distribution has no modules" else
                "modules: " + known.take(MAX_LISTED).joinToString() + if (known.size > MAX_LISTED) ", ... (${known.size} in total)" else ""
            throw IllegalArgumentException("vendor.module(): no module $wanted in the vendor distribution ($list)")
        }

        val groups = candidates.map { it.group }.distinct()
        if (groups.size > 1) {
            throw IllegalArgumentException(
                "vendor.module(): '$name' exists in several groups (${groups.joinToString()}); name the group: " +
                    "vendor.module(group, name)"
            )
        }
        if (candidates.size > 1) {
            throw IllegalArgumentException(
                "vendor.module(): '${groups.single()}:$name' is published in several versions " +
                    "(${candidates.joinToString { it.version }}); declare the dependency with its full coordinates instead"
            )
        }
        return candidates.single().notation
    }

    private const val MAX_LISTED = 20
}
