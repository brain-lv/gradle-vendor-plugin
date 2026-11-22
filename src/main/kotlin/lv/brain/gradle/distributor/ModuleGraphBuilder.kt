/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 *
 * Copyright (c) 2026 Edgars Grinvalds <edgars.grinvalds@brain.lv>
 */

package lv.brain.gradle.distributor

import lv.brain.gradle.core.ClassExcludes
import lv.brain.gradle.core.CompilePackages
import lv.brain.gradle.core.DependencyOverrides
import org.gradle.api.GradleException
import org.gradle.api.logging.Logger

internal class EdgeEvidence {
    private val byEdge = HashMap<Pair<String, String>, MutableList<String>>()

    fun add(from: String, to: String, text: String) {
        val list = byEdge.getOrPut(from to to) { ArrayList() }
        if (list.size < MAX_EXAMPLES && text !in list) list.add(text)
    }

    fun of(from: String, to: String): List<String> = byEdge[from to to].orEmpty()

    companion object {
        const val MAX_EXAMPLES = 10
    }
}

internal class ScanHit {
    var count = 0
    val examples = ArrayList<Pair<String, String>>()
}

internal data class SharedClassGroup(val jars: List<String>, val classes: List<String>)

internal class EdgeStrength {
    private val counts = HashMap<Pair<String, String>, Int>()

    fun declare(from: String, to: String) {
        counts[from to to] = DECLARED
    }

    fun add(from: String, to: String, references: Int) {
        val key = from to to
        val current = counts[key] ?: 0
        if (current != DECLARED) counts[key] = current + references
    }

    fun of(from: String, to: String): Int = counts[from to to] ?: 0

    companion object {
        const val DECLARED = Int.MAX_VALUE
    }
}

internal class ModuleGraphBuilder(
    private val inventory: List<JarMetadata>,
    private val ignoredJars: Set<String>,
    private val classExcludes: ClassExcludes,
    private val referencesOf: (JarMetadata) -> RawReferences,
    private val logger: Logger
) {
    private val mavenCoordToModuleId = mutableMapOf<String, String>()
    private val artifactIdToModuleIds = mutableMapOf<String, MutableList<String>>()
    private val byId = inventory.associateBy { it.moduleId }

    val owners = mutableMapOf<String, String>()
    val duplicateClassOwners = mutableMapOf<String, MutableList<String>>()
    val edges = LinkedHashMap<String, LinkedHashSet<String>>()
    val resolutionSources = mutableMapOf<String, String>()
    val evidence = EdgeEvidence()
    val strength = EdgeStrength()

    init {
        for (meta in inventory) {
            if (meta.moduleId in ignoredJars) {
                meta.definedClasses.forEach { classExcludes.excludes(it) }
                continue
            }
            if (meta.mavenCoord != null) mavenCoordToModuleId.putIfAbsent(meta.mavenCoord, meta.moduleId)
            artifactIdToModuleIds.getOrPut(meta.artifactId) { mutableListOf() }.add(meta.moduleId)
            for (internalClassName in meta.definedClasses) {
                if (classExcludes.excludes(internalClassName)) continue
                val owner = owners.putIfAbsent(internalClassName, meta.moduleId)
                if (owner != null && owner != meta.moduleId) {
                    duplicateClassOwners.getOrPut(internalClassName) { mutableListOf(owner) }.add(meta.moduleId)
                }
            }
        }
    }

    fun collectEdges() {
        for (meta in inventory) {
            val deps = LinkedHashSet<String>()
            resolutionSources[meta.moduleId] = when {
                meta.moduleId in ignoredJars -> "excluded"
                meta.pom != null -> { addPomEdges(meta, meta.pom, deps); "maven-pom" }
                else -> { addBytecodeEdges(meta, deps); "bytecode-scan" }
            }
            edges[meta.moduleId] = deps
        }
    }

    private fun addPomEdges(meta: JarMetadata, pom: org.w3c.dom.Element, deps: MutableSet<String>) {
        for (coord in PomModel.dependencies(pom)) {
            val exact = mavenCoordToModuleId[coord]
            val target = exact ?: uniqueByArtifactId(coord)
            if (target == null || target == meta.moduleId) continue
            deps.add(target)
            strength.declare(meta.moduleId, target)
            val note = if (exact == null) " (matched by artifactId only, the groupId differs)" else ""
            evidence.add(meta.moduleId, target, "embedded pom.xml lists dependency $coord$note")
        }
    }

    private fun addBytecodeEdges(meta: JarMetadata, deps: MutableSet<String>) {
        for ((target, scan) in scanReferences(referencesOf(meta), meta.moduleId)) {
            deps.add(target)
            strength.add(meta.moduleId, target, scan.count)
            scan.examples.forEach { (fromClass, toClass) -> evidence.add(meta.moduleId, target, describeReference(fromClass, toClass)) }
        }
    }

    fun classifyCompileJars(packages: List<String>): Map<String, String>? {
        val prefixes = CompilePackages.internalPrefixes(packages)
        if (prefixes.isEmpty()) return null

        val compile = LinkedHashMap<String, String>()
        for (meta in inventory) {
            val example = meta.definedClasses.filter { cls -> prefixes.any { cls.startsWith(it) } }.minOrNull() ?: continue
            compile[meta.moduleId] = "defines ${example.replace('/', '.')}, which is in a compile package"
        }
        val fromPackages = compile.size

        val queue = ArrayDeque(compile.keys)
        while (queue.isNotEmpty()) {
            val moduleId = queue.removeFirst()
            val meta = byId.getValue(moduleId)
            if (moduleId in ignoredJars) continue
            for ((target, reference) in ApiReferenceScanner.scan(referencesOf(meta), owners, moduleId, classExcludes::excludes)) {
                val text = "public API of ${reference.fromClass.replace('/', '.')} uses ${reference.toClass.replace('/', '.')}"
                if (edges.getValue(moduleId).add(target)) strength.add(moduleId, target, 1)
                evidence.add(moduleId, target, text)
                if (target !in compile) {
                    compile[target] = "exposed by $moduleId: $text"
                    queue.addLast(target)
                }
            }
        }

        if (fromPackages == 0) {
            logger.warn(
                "vendor.compilePackages ({}) matches no class in any selected jar, so no jar is on the compile classpath " +
                    "and nothing can be compiled against the bundles.",
                packages.joinToString()
            )
        }
        logger.lifecycle(
            "Vendor compile classpath: {} of {} jar(s) ({} from the compile packages, {} added because their public API " +
                "needs them), {} runtime only",
            compile.size, inventory.size, fromPackages, compile.size - fromPackages, inventory.size - compile.size
        )
        compile.entries.drop(fromPackages).forEach { (id, reason) -> logger.info("  promoted to compile: {} ({})", id, reason) }
        return compile
    }

    fun applyOverrides(dependencyAdd: List<String>, dependencyRemove: List<String>): Map<String, Set<String>> {
        val addedByHand = HashMap<String, MutableSet<String>>()
        val adds = dependencyAdd.map(DependencyOverrides::split)
        val removes = dependencyRemove.map(DependencyOverrides::split)
        if (adds.isEmpty() && removes.isEmpty()) return addedByHand
        val known = inventory.map { it.moduleId }.toSortedSet()
        val unknown = (adds + removes).flatMap { listOf(it.first, it.second) }.filter { it !in known }.distinct()
        if (unknown.isNotEmpty()) {
            throw GradleException(
                "vendor.dependencyAdd / vendor.dependencyRemove name unknown module(s): ${unknown.joinToString()}. " +
                    "Use the module names of module-dependencies.json: " +
                    known.take(MAX_LISTED).joinToString() + if (known.size > MAX_LISTED) ", ..." else ""
            )
        }
        val contradicting = adds.intersect(removes.toSet())
        if (contradicting.isNotEmpty()) {
            throw GradleException(
                "These dependencies are both added and removed: ${contradicting.joinToString { "${it.first}->${it.second}" }}. " +
                    "Keep each one in vendor.dependencyAdd or in vendor.dependencyRemove only."
            )
        }
        for ((from, to) in adds) {
            addedByHand.getOrPut(from) { HashSet() }.add(to)
            edges.getOrPut(from) { LinkedHashSet() }.add(to)
            strength.declare(from, to)
            evidence.add(from, to, "added by vendor.dependencyAdd")
        }
        val unmatched = removes.filterNot { (from, to) -> edges[from]?.remove(to) == true }
        if (unmatched.isNotEmpty()) {
            logger.warn(
                "vendor.dependencyRemove: {} is not a found dependency, so nothing was removed",
                unmatched.joinToString { "${it.first}->${it.second}" }
            )
        }
        logger.lifecycle("Vendor dependency overrides: {} added, {} removed", adds.size, removes.size - unmatched.size)
        return addedByHand
    }

    fun checkPoms(add: Boolean, addedByHand: Map<String, Set<String>>): List<Map<String, Any>> {
        val pomJars = inventory.filter { it.pom != null && it.moduleId !in ignoredJars }
        val result = pomJars.flatMap { meta -> pomFindings(meta, addedByHand) }.map { (meta, target, hit) ->
            val (fromClass, toClass) = hit.examples.first()
            val example = "${fromClass.replace('/', '.')} -> ${toClass.replace('/', '.')}"
            if (add) addPomCheckEdge(meta, target, hit, example) else warnPomCheck(meta, target, hit, example)
            mapOf("from" to meta.moduleId, "to" to target, "references" to hit.count, "example" to example, "added" to add)
        }
        logger.lifecycle(
            "Vendor pom check: {} jar(s) with a pom.xml checked, {} missing dependency(ies) found{}",
            pomJars.size, result.size, if (add && result.isNotEmpty()) " and added" else ""
        )
        return result
    }

    private fun pomFindings(meta: JarMetadata, addedByHand: Map<String, Set<String>>): List<Triple<JarMetadata, String, ScanHit>> {
        val mentioned = PomModel.mentions(meta.pom!!)
        val known = edges[meta.moduleId].orEmpty() + addedByHand[meta.moduleId].orEmpty()
        return scanReferences(referencesOf(meta), meta.moduleId).mapNotNull { (target, hit) ->
            val targetMeta = byId[target]
            val listed = targetMeta == null || targetMeta.artifactId in mentioned || "${targetMeta.groupId}:${targetMeta.artifactId}" in mentioned
            if (target in known || listed) null else Triple(meta, target, hit)
        }
    }

    private fun addPomCheckEdge(meta: JarMetadata, target: String, hit: ScanHit, example: String) {
        edges.getOrPut(meta.moduleId) { LinkedHashSet() }.add(target)
        strength.add(meta.moduleId, target, hit.count)
        hit.examples.forEach { (from, to) ->
            evidence.add(meta.moduleId, target, "${from.replace('/', '.')} references ${to.replace('/', '.')} (not in the pom.xml; added by vendor.pomCheck=add)")
        }
        resolutionSources[meta.moduleId] = "maven-pom+pom-check"
        logger.lifecycle("Vendor pom check: {} uses {} ({} reference(s), e.g. {}), which its pom.xml does not list; added", meta.moduleId, target, hit.count, example)
    }

    private fun warnPomCheck(meta: JarMetadata, target: String, hit: ScanHit, example: String) {
        logger.warn(
            "Vendor pom check: {} uses {} ({} reference(s), e.g. {}), which its pom.xml does not list. " +
                "If {} needs it, add vendor.dependencyAdd={}->{}, or set vendor.pomCheck=add",
            meta.moduleId, target, hit.count, example, meta.moduleId, meta.moduleId, target
        )
    }

    fun sharedClassGroups(): List<SharedClassGroup> =
        duplicateClassOwners.entries
            .groupBy({ it.value.distinct() }, { it.key })
            .map { (jars, classes) -> SharedClassGroup(jars, classes.sorted()) }
            .sortedWith(compareByDescending<SharedClassGroup> { it.classes.size }.thenBy { it.jars.joinToString() })

    fun reportSharedClasses() {
        if (duplicateClassOwners.isEmpty()) return
        val groups = sharedClassGroups()
        logger.lifecycle(
            "Vendor: {} class(es) are defined in more than one jar ({} group(s) of jars). A jar's references to its own " +
                "copy stay inside it; references from other jars go to the first jar of each group. --info lists them.",
            duplicateClassOwners.size, groups.size
        )
        for (group in groups) {
            logger.info(
                "  {}: {} shared class(es), e.g. {}",
                group.jars.joinToString(", "), group.classes.size, group.classes.take(3).joinToString { it.replace('/', '.') }
            )
        }
    }

    private fun scanReferences(raw: RawReferences, selfModule: String): Map<String, ScanHit> {
        val found = java.util.TreeMap<String, ScanHit>()
        for ((fromClass, targets) in raw.body) {
            if (classExcludes.excludes(fromClass)) continue
            for (toClass in targets) {
                val owner = owners[toClass] ?: continue
                if (owner == selfModule) continue
                val hit = found.getOrPut(owner) { ScanHit() }
                hit.count++
                if (hit.examples.size < EdgeEvidence.MAX_EXAMPLES) hit.examples.add(fromClass to toClass)
            }
        }
        return found
    }

    private fun uniqueByArtifactId(coord: String): String? {
        val artifactId = coord.substringAfter(':', "")
        if (artifactId.isEmpty()) return null
        val candidates = artifactIdToModuleIds[artifactId].orEmpty()
        return if (candidates.size == 1) candidates.first() else null
    }

    private fun describeReference(fromClass: String, toClass: String): String {
        val base = "${fromClass.replace('/', '.')} references ${toClass.replace('/', '.')}"
        val sharing = duplicateClassOwners[toClass] ?: return base
        return "$base (that class is defined in several jars: ${sharing.joinToString()}; the first one is used)"
    }

    private companion object {
        const val MAX_LISTED = 20
    }
}
