/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 *
 * Copyright (c) 2026 Edgars Grinvalds <edgars.grinvalds@brain.lv>
 */

package lv.brain.gradle.distributor

import java.io.File
import lv.brain.gradle.core.GroupRule
import org.gradle.api.logging.Logger
import org.w3c.dom.Element

internal const val ORIGIN_UNKNOWN = "unknown"

internal data class JarMetadata(
    val moduleId: String,
    val groupId: String,
    val artifactId: String,
    val version: String,
    val file: File,
    val mavenCoord: String?,
    val hasEmbeddedPom: Boolean,
    val definedClasses: Set<String>,
    val categories: List<String> = emptyList(),
    val pom: Element? = null,
    val pluginIds: List<String> = emptyList(),
    val identity: String = VendorJarScanner.IDENTITY_FILE_NAME,
    val contentKey: String = "",
    val licenses: List<String> = emptyList(),
    val moduleName: String? = null,
    val moduleKind: String = "none",
    val origin: String = ORIGIN_UNKNOWN
) {
    val isOpenSource: Boolean
        get() = origin == GroupRule.OPEN_SOURCE ||
            (origin == ORIGIN_UNKNOWN && (identity == VendorJarScanner.IDENTITY_POM_PROPERTIES || identity == VendorJarScanner.IDENTITY_POM_XML))
}

internal object VendorInventory {
    fun build(
        selection: JarSelection,
        defaultGroup: String,
        defaultVersion: String,
        groupRules: List<GroupRule>,
        logger: Logger
    ): List<JarMetadata> {
        class Candidate(val jar: SelectedJar, val facts: JarFacts, val coords: JarCoordinates, val identity: String)

        val identified = selection.jars.parallelStream().map { jar ->
            val facts = VendorJarScanner.readFacts(jar.file)
            val (coords, identity) = VendorJarScanner.identify(jar.file, facts, jar.category, defaultGroup, defaultVersion, groupRules)
            Candidate(jar, facts, coords, identity)
        }.toList()
        val byModule = identified.groupBy { "${it.coords.group}:${it.coords.name}:${it.coords.version}" }

        for ((gav, duplicates) in byModule) {
            if (duplicates.size < 2) continue
            val first = duplicates.first()
            val different = duplicates.drop(1).filterNot { VendorJarScanner.sameContent(first.jar.file, it.jar.file) }
            if (different.isNotEmpty()) {
                logger.warn(
                    "Vendor: $gav is found as different files, only ${first.jar.relativePath} is used " +
                        "(ignored: ${different.joinToString { it.jar.relativePath }}). " +
                        "Give them distinct identities with a vendor.rule.<n> or fix their names/manifests."
                )
            }
        }

        val usedModuleIds = mutableSetOf<String>()
        return byModule.values.map { duplicates ->
            val first = duplicates.first()
            var candidateId = first.coords.name
            var suffix = 2
            while (!usedModuleIds.add(candidateId)) {
                candidateId = "${first.coords.name}-$suffix"
                suffix++
            }
            val pom = first.facts.pomProperties
            JarMetadata(
                moduleId = candidateId,
                groupId = first.coords.group,
                artifactId = first.coords.name,
                version = first.coords.version,
                file = first.jar.file,
                mavenCoord = pom?.let { "${it.first}:${it.second}" },
                hasEmbeddedPom = first.facts.singlePom != null,
                definedClasses = first.facts.definedClasses,
                categories = duplicates.map { it.jar.category }.distinct(),
                pom = first.facts.singlePom,
                pluginIds = first.facts.pluginIds,
                identity = first.identity,
                contentKey = first.facts.contentKey,
                licenses = PomModel.licenses(first.facts),
                moduleName = first.facts.explicitModuleName ?: first.facts.automaticModuleName,
                moduleKind = when {
                    first.facts.explicitModuleName != null -> "explicit"
                    first.facts.automaticModuleName != null -> "automatic"
                    else -> "none"
                },
                origin = if (first.identity == VendorJarScanner.IDENTITY_RULE) {
                    VendorJarScanner.matchingRule(first.jar.file, groupRules)?.first?.origin ?: ORIGIN_UNKNOWN
                } else ORIGIN_UNKNOWN
            )
        }
    }
}
