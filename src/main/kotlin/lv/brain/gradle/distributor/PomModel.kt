/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 *
 * Copyright (c) 2026 Edgars Grinvalds <edgars.grinvalds@brain.lv>
 */

package lv.brain.gradle.distributor

import org.w3c.dom.Element

internal object PomModel {
    private val PLACEHOLDER = Regex("\\$\\{([^}]+)}")

    fun dependencies(root: Element): Set<String> {
        val coords = LinkedHashSet<String>()
        val props = properties(root)
        val childNodes = root.childNodes
        for (i in 0 until childNodes.length) {
            val node = childNodes.item(i) as? Element ?: continue
            if (node.tagName != "dependencies") continue
            val depNodes = node.getElementsByTagName("dependency")
            for (j in 0 until depNodes.length) {
                val el = depNodes.item(j) as? Element ?: continue
                val scope = childText(el, "scope") ?: "compile"
                val optional = childText(el, "optional")?.equals("true", ignoreCase = true) == true
                val groupId = resolve(childText(el, "groupId"), props)
                val artifactId = resolve(childText(el, "artifactId"), props)
                if (!optional &&
                    (scope == "compile" || scope == "runtime") &&
                    !groupId.isNullOrBlank() &&
                    !artifactId.isNullOrBlank()
                ) {
                    coords.add("$groupId:$artifactId")
                }
            }
        }
        return coords
    }

    fun mentions(root: Element): Set<String> {
        val props = properties(root)
        val result = HashSet<String>()
        val nodes = root.getElementsByTagName("dependency")
        for (i in 0 until nodes.length) {
            val el = nodes.item(i) as? Element ?: continue
            val artifact = resolve(childText(el, "artifactId"), props) ?: continue
            result += artifact
            resolve(childText(el, "groupId"), props)?.let { result += "$it:$artifact" }
        }
        return result
    }

    fun licenses(facts: JarFacts): List<String> {
        val result = LinkedHashSet<String>()
        facts.singlePom?.let { pom ->
            val licenses = child(pom, "licenses")
            val nodes = licenses?.getElementsByTagName("license")
            for (i in 0 until (nodes?.length ?: 0)) {
                val el = nodes!!.item(i) as? Element ?: continue
                (childText(el, "name") ?: childText(el, "url"))?.let { result += it }
            }
        }
        facts.manifest?.getValue("Bundle-License")?.split(',')?.map { it.substringBefore(';').trim() }
            ?.filter { it.isNotEmpty() }?.forEach { result += it }
        return result.toList()
    }

    private fun properties(root: Element): Map<String, String> {
        val props = mutableMapOf<String, String>()
        val projectGroupId = childText(root, "groupId") ?: child(root, "parent")?.let { childText(it, "groupId") }
        val projectVersion = childText(root, "version") ?: child(root, "parent")?.let { childText(it, "version") }
        if (!projectGroupId.isNullOrBlank()) {
            props["project.groupId"] = projectGroupId
            props["pom.groupId"] = projectGroupId
        }
        if (!projectVersion.isNullOrBlank()) {
            props["project.version"] = projectVersion
            props["pom.version"] = projectVersion
        }
        val propertiesEl = child(root, "properties")
        if (propertiesEl != null) {
            val children = propertiesEl.childNodes
            for (i in 0 until children.length) {
                val propEl = children.item(i) as? Element ?: continue
                val value = propEl.textContent?.trim()
                if (!value.isNullOrEmpty()) props[propEl.tagName] = value
            }
        }
        return props
    }

    private fun resolve(value: String?, props: Map<String, String>): String? {
        if (value == null) return null
        var resolved = value.trim()
        repeat(3) {
            resolved = PLACEHOLDER.replace(resolved) { match -> props[match.groupValues[1]] ?: match.value }
        }
        return resolved
    }

    private fun child(parent: Element, tagName: String): Element? {
        val children = parent.childNodes
        for (i in 0 until children.length) {
            val element = children.item(i) as? Element ?: continue
            if (element.tagName == tagName) return element
        }
        return null
    }

    private fun childText(parent: Element, tagName: String): String? =
        child(parent, tagName)?.textContent?.trim()?.takeIf { it.isNotEmpty() }
}
