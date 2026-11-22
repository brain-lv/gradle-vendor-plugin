/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 *
 * Copyright (c) 2026 Edgars Grinvalds <edgars.grinvalds@brain.lv>
 */

package lv.brain.gradle.distributor

import java.io.File
import java.util.TreeMap
import java.util.TreeSet
import lv.brain.gradle.core.ClassExcludes
import lv.brain.gradle.core.GroupRule
import lv.brain.gradle.core.LicensePolicy
import org.gradle.api.GradleException
import org.gradle.api.logging.Logger

internal const val POM_CHECK_OFF = "off"
internal const val POM_CHECK_WARN = "warn"
internal const val POM_CHECK_ADD = "add"
internal const val MISSING_WARN = "warn"
internal const val MISSING_FAIL = "fail"
internal const val MISSING_OFF = "off"
internal const val SCOPE_VENDOR = "vendor"
internal const val SCOPE_ALL = "all"

internal class VendorJarChecks(
    private val inventory: List<JarMetadata>,
    private val ignoredJars: Set<String>,
    private val logger: Logger
) {
    fun reportDefaultGroups(rootDir: File, defaultGroup: String) {
        val defaulted = inventory.filter { meta ->
            meta.identity == VendorJarScanner.IDENTITY_FILE_NAME ||
                (meta.identity == VendorJarScanner.IDENTITY_MANIFEST && meta.groupId == defaultGroup)
        }
        if (defaulted.isEmpty()) return
        val names = defaulted.map { it.file.relativeTo(rootDir).invariantSeparatorsPath }
        logger.warn(
            "Vendor: {} jar(s) got the default group because no pom, manifest group or vendor.rule.<n> names them: {}{}. " +
                "Give them their real coordinates with a rule such as vendor.rule.1=^(name-prefix.*?)-(\\d.*)\\.jar$ -> <group> " +
                "open-source, vendor-public or vendor-private.",
            defaulted.size, names.take(10).joinToString(), if (names.size > 10) ", ..." else ""
        )
    }

    fun checkModulePath(mode: String): Map<String, Any> {
        val jars = inventory.filter { it.moduleId !in ignoredJars }
        val duplicateNames = jars.filter { it.moduleName != null }
            .groupBy({ it.moduleName!! }, { it.moduleId })
            .filterValues { it.size > 1 }
            .toSortedMap()
        val named = jars.filter { it.moduleName != null }.mapTo(HashSet()) { it.moduleId }
        val split = packageOwners(jars).filterValues { it.size > 1 }
        val namedSplit = split.filterValues { owners -> owners.count { it in named } > 1 }

        modulePathProblems(duplicateNames, namedSplit)?.let { message ->
            if (mode == MISSING_FAIL) throw GradleException(message)
            logger.warn(message)
        }
        return linkedMapOf(
            "namedModules" to jars.filter { it.moduleName != null }.associate { it.moduleId to it.moduleName },
            "duplicateModuleNames" to duplicateNames.map { (name, ids) -> mapOf("moduleName" to name, "jars" to ids) },
            "splitPackages" to split.map { (pkg, ids) -> mapOf("package" to pkg, "jars" to ids.toList(), "betweenNamedModules" to (pkg in namedSplit)) }
        )
    }

    private fun packageOwners(jars: List<JarMetadata>): Map<String, Set<String>> {
        val byPackage = TreeMap<String, TreeSet<String>>()
        for (meta in jars) {
            meta.definedClasses.map { it.substringBeforeLast('/', "") }.filter { it.isNotEmpty() }.forEach { pkg ->
                byPackage.getOrPut(pkg.replace('/', '.')) { TreeSet() } += meta.moduleId
            }
        }
        return byPackage
    }

    internal fun modulePathProblems(duplicateNames: Map<String, List<String>>, namedSplit: Map<String, Set<String>>): String? {
        val parts = listOfNotNull(
            duplicateNames.takeIf { it.isNotEmpty() }?.let {
                "${it.size} module name(s) are used by more than one jar (" + firstThree(it) { name, ids -> "$name: ${ids.joinToString()}" } + ")"
            },
            namedSplit.takeIf { it.isNotEmpty() }?.let {
                "${it.size} package(s) are split between named modules (" + firstThree(it) { pkg, ids -> "$pkg in ${ids.joinToString()}" } + ")"
            }
        )
        if (parts.isEmpty()) return null
        return "Vendor module path check: ${parts.joinToString(" and ")}. Such jars work on the class path, but " +
            "on the Java module path the application fails to start. Keep only one of the jars, or run on the class path " +
            "and set vendor.jpmsCheck=off. module-dependencies.json lists them under jpms."
    }

    private fun <V> firstThree(map: Map<String, V>, render: (String, V) -> String): String =
        map.entries.take(3).joinToString("; ") { (key, value) -> render(key, value) } + if (map.size > 3) "; ..." else ""

    fun findMissingClasses(
        classExcludes: ClassExcludes,
        referencesOf: (JarMetadata) -> RawReferences,
        mode: String,
        scope: String
    ): List<Map<String, Any>> {
        val byPackage = collectMissing(classExcludes, referencesOf)
        if (byPackage.isEmpty()) return emptyList()

        val byId = inventory.associateBy { it.moduleId }
        fun openSourceOnly(m: MissingPackage) = m.usedBy.all { id -> byId[id]?.isOpenSource == true }
        val reported = byPackage.filter { (_, m) -> scope == SCOPE_ALL || !openSourceOnly(m) }
        val optional = byPackage.filterKeys { it !in reported }

        byPackage.forEach { (pkg, m) ->
            logger.info("  missing {}: {} (used by {})", pkg.replace('/', '.'), m.classes.take(10).joinToString { it.replace('/', '.') }, m.usedBy.joinToString())
        }
        if (reported.isNotEmpty()) {
            val message = missingMessage(reported)
            if (mode == MISSING_FAIL) throw GradleException(message)
            logger.warn(message)
        }
        if (optional.isNotEmpty()) {
            logger.lifecycle(
                "Vendor: {} class(es) in {} package(s) that only open-source jars use are not in the distribution, usually optional " +
                    "dependencies of those libraries (--info lists them; vendor.missingClasses.scope=all reports them as missing).",
                optional.values.sumOf { it.classes.size }, optional.size
            )
        }
        return byPackage.map { (pkg, m) ->
            mapOf(
                "package" to pkg.replace('/', '.'),
                "classCount" to m.classes.size,
                "classes" to m.classes.take(EdgeEvidence.MAX_EXAMPLES).map { it.replace('/', '.') },
                "usedBy" to m.usedBy.toList(),
                "openSourceOnly" to openSourceOnly(m)
            )
        }
    }

    internal class MissingPackage(val classes: TreeSet<String> = TreeSet(), val usedBy: TreeSet<String> = TreeSet())

    private fun collectMissing(classExcludes: ClassExcludes, referencesOf: (JarMetadata) -> RawReferences): TreeMap<String, MissingPackage> {
        val defined = inventory.flatMapTo(HashSet()) { it.definedClasses }
        val jdkPackages = HashSet<String>()
        ModuleLayer.boot().modules().forEach { module -> module.packages.forEach { jdkPackages += it.replace('.', '/') } }
        val byPackage = TreeMap<String, MissingPackage>()
        inventory.filter { it.moduleId !in ignoredJars }.forEach { meta ->
            missingTargets(referencesOf(meta), classExcludes, defined).forEach { target ->
                val pkg = target.substringBeforeLast('/', "")
                if (pkg !in jdkPackages && !classExcludes.excludes(target)) {
                    val entry = byPackage.getOrPut(pkg) { MissingPackage() }
                    entry.classes += target
                    entry.usedBy += meta.moduleId
                }
            }
        }
        return byPackage
    }

    private fun missingTargets(raw: RawReferences, classExcludes: ClassExcludes, defined: Set<String>): Sequence<String> =
        raw.body.asSequence()
            .filter { (fromClass, _) -> !classExcludes.excludes(fromClass) }
            .flatMap { (fromClass, targets) ->
                val annotationsOnly = raw.annotations[fromClass].orEmpty().toSet()
                targets.asSequence().filter { !it.startsWith("[") && it !in defined && it !in annotationsOnly }
            }

    internal fun missingMessage(reported: Map<String, MissingPackage>): String {
        val summary = reported.entries.take(5).joinToString("; ") { (pkg, m) ->
            val users = m.usedBy.take(3).joinToString() + if (m.usedBy.size > 3) ", ..." else ""
            "${pkg.replace('/', '.').ifEmpty { "(default package)" }} (${m.classes.size} class(es), used by $users)"
        } + if (reported.size > 5) "; ..." else ""
        return "Vendor: ${reported.values.sumOf { it.classes.size }} class(es) in ${reported.size} package(s) are used by vendor jars " +
            "but are in no vendor jar and not in the JDK (Java ${Runtime.version().feature()}): $summary. Add the missing jars " +
            "to the vendor folder, leave them out with vendor.dependencyExcludeClasses if they are optional, or set " +
            "vendor.missingClasses=off. --info lists the classes; module-dependencies.json has them under missingClasses."
    }

    fun checkLicenses(policy: LicensePolicy): Map<String, Any>? {
        if (!policy.active) return null
        val checked = inventory.filter { it.origin != GroupRule.VENDOR_PRIVATE }
        val violations = checked.filter { it.licenses.isNotEmpty() && !policy.accepts(it.licenses) }
        val unknown = checked.filter { it.licenses.isEmpty() }
        if (unknown.isNotEmpty() && policy.unknown != MISSING_OFF) {
            val message = "Vendor licence policy: ${unknown.size} jar(s) declare no licence in their pom or manifest: " +
                unknown.take(10).joinToString { it.moduleId } + (if (unknown.size > 10) ", ..." else "") +
                ". verifyVendorAgainstMaven reads the licences of the published pom.xml; mark the vendor's own jars vendor-private, " +
                "or set vendor.licenses.unknown=off."
            if (policy.unknown == MISSING_FAIL) throw GradleException(message)
            logger.warn(message)
        }
        if (violations.isNotEmpty()) {
            throw GradleException(
                "Vendor licence policy: ${violations.size} jar(s) have a licence that vendor.licenses.allow / vendor.licenses.deny " +
                    "do not accept:\n" + violations.joinToString("\n") { meta ->
                        "  ${meta.file.name} (${meta.groupId}:${meta.artifactId}:${meta.version}): " +
                            meta.licenses.joinToString { "$it [${LicensePolicy.normalize(it)}]" }
                    } + "\nRemove the jars, accept the licence in vendor.licenses.allow, or mark the vendor's own code vendor-private."
            )
        }
        return linkedMapOf(
            "allow" to policy.allow,
            "deny" to policy.deny,
            "unknown" to unknown.map { it.moduleId }
        )
    }
}
