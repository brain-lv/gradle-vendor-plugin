/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 *
 * Copyright (c) 2026 brain-lv (https://github.com/brain-lv)
 */

package lv.brain.gradle.distributor

import groovy.json.JsonOutput
import lv.brain.gradle.core.CategoryRule
import lv.brain.gradle.core.ClassExcludes
import lv.brain.gradle.core.CompilePackages
import lv.brain.gradle.core.GroupRule
import lv.brain.gradle.core.JarExcludes
import org.gradle.api.DefaultTask
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.FileTree
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.ListProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.CacheableTask
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputFiles
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.Optional
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import org.objectweb.asm.ClassReader
import org.objectweb.asm.ClassVisitor
import org.objectweb.asm.FieldVisitor
import org.objectweb.asm.MethodVisitor
import org.objectweb.asm.Opcodes
import org.objectweb.asm.RecordComponentVisitor
import org.objectweb.asm.commons.ClassRemapper
import org.objectweb.asm.commons.Remapper
import org.w3c.dom.Element
import java.io.ByteArrayInputStream
import java.io.File
import java.util.jar.JarFile

/**
 * The `resolveVendorModules` task: selects the vendor jars and resolves the dependencies between them.
 *
 * Each jar below [vendorDir] is assigned to the first category whose include pattern matches it. The result, which
 * jars belong to the distribution and which of them each one needs, is written to [dependencyGraphFile].
 */
@CacheableTask
abstract class ResolveModuleDependenciesTask : DefaultTask() {
    /** The directory that holds the vendor jars. */
    @get:Internal
    abstract val vendorDir: DirectoryProperty

    /** The jars below [vendorDir]; the generated `ivy/` folder is not included. */
    @get:InputFiles
    @get:PathSensitive(PathSensitivity.RELATIVE)
    val vendorJars: FileTree
        get() = VendorJarScanner.jarTree(vendorDir)

    /** The name of [vendorDir]; include patterns may start with it. */
    @get:Input
    val vendorDirName: String
        get() = vendorDir.get().asFile.name

    /** Base group for jars without embedded coordinates. */
    @get:Input
    @get:Optional
    abstract val defaultOrg: Property<String>

    /** Version for jars whose file name carries none. */
    @get:Input
    @get:Optional
    abstract val defaultVersion: Property<String>

    /** When `true`, looks harder for the dependencies of jars whose own metadata lists none. */
    @get:Input
    @get:Optional
    abstract val hybridBytecodeFallback: Property<Boolean>

    /** The category include patterns, in declaration order. */
    @get:Input
    abstract val categoryRules: ListProperty<CategoryRule>

    /** Regex rules (`vendor.rule.<n>`) that give a group to jars without embedded coordinates. */
    @get:Input
    abstract val groupRules: ListProperty<GroupRule>

    /** Package prefixes (`com.vendor`) of the jars that are compiled against; empty means all jars are. */
    @get:Input
    abstract val compilePackages: ListProperty<String>

    /** Classes and packages that the dependency check ignores. */
    @get:Input
    abstract val dependencyExcludeClasses: ListProperty<String>

    /** Jars (path prefix or Ant-style pattern) that the dependency check ignores. */
    @get:Input
    abstract val dependencyExcludeJars: ListProperty<String>

    /** Category for jars that match no include, unless [skipUnclassified] is `true`. */
    @get:Input
    abstract val fallbackCategory: Property<String>

    /** When `true`, jars that match no category include are left out. */
    @get:Input
    abstract val skipUnclassified: Property<Boolean>

    /** The classifier from `vendorDistributor { classifier { ... } }`, or `null`. */
    @get:Internal
    var classifier: CategoryClassifier? = null

    /** The resolved module graph (JSON), read by `generateVendorIvyRepo` and `packageVendorDistribution`. */
    @get:OutputFile
    abstract val dependencyGraphFile: RegularFileProperty

    init {
        defaultOrg.convention("lv.brain.vendor")
        defaultVersion.convention("1.0.0")
        hybridBytecodeFallback.convention(true)
        fallbackCategory.convention("core")
        skipUnclassified.convention(false)
        compilePackages.convention(emptyList())
        dependencyExcludeClasses.convention(emptyList())
        dependencyExcludeJars.convention(emptyList())
    }

    data class JarMetadata(
        val moduleId: String,
        val groupId: String,
        val artifactId: String,
        val version: String,
        val file: File,
        val mavenCoord: String?,
        val hasEmbeddedPom: Boolean,
        val definedClasses: Set<String>,
        val categories: List<String> = emptyList(),
        val pomXmls: List<ByteArray> = emptyList()
    )

    /** Selects the jars, resolves their dependencies and writes [dependencyGraphFile]. */
    @TaskAction
    fun resolveDependencies() {
        val rootDir = vendorDir.get().asFile
        val rules = categoryRules.get()
        val selection = VendorJarScanner.selectJars(
            vendorRoot = rootDir,
            rules = rules,
            classifier = classifier,
            fallbackCategory = if (skipUnclassified.get()) null else fallbackCategory.get()
        )
        reportSelection(rules, selection)

        if (selection.jars.isEmpty()) {
            writeEmptyGraph(selection.skipped.size)
            return
        }

        val inventory = buildInventory(selection)

        val classExcludes = ClassExcludes(dependencyExcludeClasses.get())
        val jarExcludes = JarExcludes(dependencyExcludeJars.get(), rootDir.name)
        val ignoredJars: Set<String> = inventory
            .filter { jarExcludes.excludes(it.file.relativeTo(rootDir).invariantSeparatorsPath) }
            .mapTo(LinkedHashSet()) { it.moduleId }

        val mavenCoordToModuleId = mutableMapOf<String, String>()
        val artifactIdToModuleIds = mutableMapOf<String, MutableList<String>>()
        val classToModuleId = mutableMapOf<String, String>()
        val duplicateClassOwners = mutableMapOf<String, MutableList<String>>()

        for (meta in inventory) {
            if (meta.moduleId in ignoredJars) {
                meta.definedClasses.forEach { classExcludes.excludes(it) }
                continue
            }
            if (meta.mavenCoord != null) {
                mavenCoordToModuleId.putIfAbsent(meta.mavenCoord, meta.moduleId)
            }
            artifactIdToModuleIds.getOrPut(meta.artifactId) { mutableListOf() }.add(meta.moduleId)

            for (internalClassName in meta.definedClasses) {
                if (classExcludes.excludes(internalClassName)) continue
                val owner = classToModuleId.putIfAbsent(internalClassName, meta.moduleId)
                if (owner != null && owner != meta.moduleId) {
                    duplicateClassOwners.getOrPut(internalClassName) { mutableListOf(owner) }.add(meta.moduleId)
                }
            }
        }

        val rawEdges = LinkedHashMap<String, LinkedHashSet<String>>()
        val resolutionSources = mutableMapOf<String, String>()
        val evidence = EdgeEvidence()

        for (meta in inventory) {
            val deps = LinkedHashSet<String>()
            var source = "none"

            if (meta.moduleId in ignoredJars) {
                rawEdges[meta.moduleId] = deps
                resolutionSources[meta.moduleId] = "excluded"
                continue
            }

            if (meta.hasEmbeddedPom) {
                val mavenCoords = extractPomDependencies(meta.pomXmls)
                for (coord in mavenCoords) {
                    val exact = mavenCoordToModuleId[coord]
                    val targetModule = exact ?: resolveUniqueArtifactFallback(coord, artifactIdToModuleIds)
                    if (targetModule != null && targetModule != meta.moduleId) {
                        deps.add(targetModule)
                        evidence.add(
                            meta.moduleId, targetModule,
                            "embedded pom.xml lists dependency $coord" +
                                if (exact == null) " (matched by artifactId only, the groupId differs)" else ""
                        )
                    }
                }
                source = "maven-pom"
            }

            val shouldRunBytecodeScan = !meta.hasEmbeddedPom ||
                (hybridBytecodeFallback.get() && deps.isEmpty())

            if (shouldRunBytecodeScan) {
                var bytecodeHits = 0
                for ((targetModule, references) in scanReferences(meta.file, classToModuleId, meta.moduleId, classExcludes::excludes)) {
                    if (deps.add(targetModule)) {
                        bytecodeHits++
                    }
                    for ((fromClass, toClass) in references) {
                        evidence.add(meta.moduleId, targetModule, describeReference(fromClass, toClass, duplicateClassOwners))
                    }
                }
                source = when {
                    !meta.hasEmbeddedPom -> "bytecode-scan"
                    bytecodeHits > 0 -> "maven-pom+bytecode-fallback"
                    else -> "maven-pom"
                }
            }

            rawEdges[meta.moduleId] = deps
            resolutionSources[meta.moduleId] = source
        }

        val compileReasons: Map<String, String>? = classifyCompileJars(inventory, classToModuleId, rawEdges, evidence, ignoredJars, classExcludes)
        reportExcludes(classExcludes, jarExcludes, ignoredJars)

        val (acyclicEdges, brokenCycles) = breakCycles(rawEdges)
        reportBrokenCycles(brokenCycles, evidence)
        val topologicalOrder = topologicalSort(acyclicEdges)
        val metaById = inventory.associateBy { it.moduleId }

        val reduction = DependencyReduction.reduce(acyclicEdges, topologicalOrder)
        reportRedundantEdges(reduction, acyclicEdges)

        val apiEdges: Map<String, LinkedHashSet<String>>? = compileReasons?.let { compile ->
            val induced = acyclicEdges.filterKeys { it in compile }
                .mapValuesTo(LinkedHashMap()) { (_, targets) -> targets.filterTo(LinkedHashSet()) { it in compile } }
            DependencyReduction.reduce(induced, topologicalOrder.filter { it in compile }).edges
        }

        val modulesPayload = topologicalOrder.mapNotNull { id ->
            val meta = metaById[id] ?: return@mapNotNull null
            val deps = reduction.edges[id]?.toList().orEmpty()
            val apiDeps = apiEdges?.get(id)?.toList()
            fun ref(depId: String): Map<String, String>? = metaById[depId]?.let { depMeta ->
                mapOf(
                    "moduleId" to depMeta.moduleId,
                    "groupId" to depMeta.groupId,
                    "artifactId" to depMeta.artifactId,
                    "version" to depMeta.version
                )
            }
            mapOf(
                "moduleId" to meta.moduleId,
                "groupId" to meta.groupId,
                "artifactId" to meta.artifactId,
                "version" to meta.version,
                "jar" to meta.file.relativeTo(rootDir).invariantSeparatorsPath,
                "category" to meta.categories.first(),
                "categories" to meta.categories,
                "resolutionSource" to (resolutionSources[id] ?: "none"),
                "compile" to (compileReasons == null || id in compileReasons),
                "compileReason" to compileReasons?.get(id),
                "mavenCoord" to meta.mavenCoord,
                "dependsOn" to deps.mapNotNull(::ref),
                "apiDependsOn" to apiDeps?.mapNotNull(::ref)
            )
        }

        val payload = mapOf(
            "schemaVersion" to 1,
            "moduleCount" to modulesPayload.size,
            "skippedJarCount" to selection.skipped.size,
            "topologicalOrder" to topologicalOrder,
            "compileSplit" to (compileReasons != null),
            "compilePackages" to compilePackages.get(),
            "dependencyExcludes" to mapOf(
                "classes" to dependencyExcludeClasses.get(),
                "jars" to dependencyExcludeJars.get(),
                "ignoredJars" to ignoredJars.toList()
            ),
            "modules" to modulesPayload,
            "redundantEdges" to reduction.redundant.map { mapOf("from" to it.from, "to" to it.to, "via" to it.via) },
            "brokenCycles" to brokenCycles.map { cycle ->
                mapOf(
                    "removedEdge" to mapOf("from" to cycle.removedFrom, "to" to cycle.removedTo),
                    "cycle" to cycle.path,
                    "edges" to cycle.edges.map { (from, to) ->
                        mapOf(
                            "from" to from,
                            "to" to to,
                            "removed" to (from == cycle.removedFrom && to == cycle.removedTo),
                            "evidence" to evidence.of(from, to)
                        )
                    }
                )
            }
        )

        val outFile = dependencyGraphFile.get().asFile
        outFile.parentFile.mkdirs()
        outFile.writeText(JsonOutput.prettyPrint(JsonOutput.toJson(payload)))
    }

    private fun reportSelection(rules: List<CategoryRule>, selection: JarSelection) {
        val perCategory = selection.jars.groupingBy { it.category }.eachCount()
        val summary = perCategory.entries.joinToString(", ") { "${it.key}=${it.value}" }
        logger.lifecycle("Vendor jars: {} selected ({}), {} skipped", selection.jars.size, summary, selection.skipped.size)

        rules.filter { it.category !in perCategory }.forEach { rule ->
            logger.warn(
                "Vendor category '{}' matched no jars. Patterns are relative to the vendor directory: {}",
                rule.category,
                rule.patterns.joinToString()
            )
        }
        if (selection.skipped.isNotEmpty()) {
            logger.lifecycle(
                "{} jar(s) match no category include and are left out (add an include for them, " +
                    "or set vendor.defaultCategory to keep them). Run with --info to list them.",
                selection.skipped.size
            )
            selection.skipped.forEach { logger.info("  skipped: {}", it) }
        }
    }

    private fun writeEmptyGraph(skippedJarCount: Int) {
        val outFile = dependencyGraphFile.get().asFile
        outFile.parentFile.mkdirs()
        outFile.writeText(
            JsonOutput.prettyPrint(
                JsonOutput.toJson(
                    mapOf(
                        "schemaVersion" to 1,
                        "moduleCount" to 0,
                        "skippedJarCount" to skippedJarCount,
                        "topologicalOrder" to emptyList<String>(),
                        "compileSplit" to false,
                        "compilePackages" to compilePackages.get(),
                        "modules" to emptyList<Any>(),
                        "redundantEdges" to emptyList<Any>(),
                        "brokenCycles" to emptyList<Any>()
                    )
                )
            )
        )
    }

    private fun buildInventory(selection: JarSelection): List<JarMetadata> {
        val defaultGroup = defaultOrg.get()
        val defaultVer = defaultVersion.get()
        val groupRuleList = groupRules.get()

        class Candidate(val jar: SelectedJar, val facts: JarFacts, val coords: JarCoordinates)

        val byModule = LinkedHashMap<String, MutableList<Candidate>>()
        for (jar in selection.jars) {
            val facts = VendorJarScanner.readFacts(jar.file)
            val coords = VendorJarScanner.resolveCoordinates(
                jar.file, facts, jar.category, defaultGroup, defaultVer, groupRuleList
            )
            byModule.getOrPut("${coords.group}:${coords.name}:${coords.version}") { mutableListOf() }
                .add(Candidate(jar, facts, coords))
        }

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
                hasEmbeddedPom = first.facts.pomXmls.isNotEmpty(),
                definedClasses = first.facts.definedClasses,
                categories = duplicates.map { it.jar.category }.distinct(),
                pomXmls = first.facts.pomXmls
            )
        }
    }

    private fun resolveUniqueArtifactFallback(
        coord: String,
        artifactIdToModuleIds: Map<String, List<String>>
    ): String? {
        val artifactId = coord.substringAfter(':', "")
        if (artifactId.isEmpty()) return null
        val candidates = artifactIdToModuleIds[artifactId].orEmpty()
        return if (candidates.size == 1) candidates.first() else null
    }

    private fun extractPomDependencies(pomXmls: List<ByteArray>): Set<String> {
        val coords = LinkedHashSet<String>()
        val dbFactory = VendorJarScanner.secureDocumentBuilderFactory()

        for (pomBytes in pomXmls) {
            val doc = dbFactory.newDocumentBuilder().parse(ByteArrayInputStream(pomBytes))
            val root = doc.documentElement
            val props = collectPomProperties(root)

            val childNodes = root.childNodes
            for (i in 0 until childNodes.length) {
                val node = childNodes.item(i) as? Element ?: continue
                if (node.tagName == "dependencies") {
                    val depNodes = node.getElementsByTagName("dependency")
                    for (j in 0 until depNodes.length) {
                        val el = depNodes.item(j) as? Element ?: continue
                        val rawGroupId = directChildText(el, "groupId")
                        val rawArtifactId = directChildText(el, "artifactId")
                        val scope = directChildText(el, "scope") ?: "compile"
                        val optional = directChildText(el, "optional")?.equals("true", ignoreCase = true) == true

                        val groupId = resolvePlaceholders(rawGroupId, props)
                        val artifactId = resolvePlaceholders(rawArtifactId, props)

                        if (!optional &&
                            (scope == "compile" || scope == "runtime") &&
                            !groupId.isNullOrBlank() &&
                            !artifactId.isNullOrBlank()
                        ) {
                            coords.add("$groupId:$artifactId")
                        }
                    }
                }
            }
        }
        return coords
    }

    private fun collectPomProperties(root: Element): Map<String, String> {
        val props = mutableMapOf<String, String>()
        val projectGroupId = directChildText(root, "groupId")
            ?: directChildElement(root, "parent")?.let { directChildText(it, "groupId") }
        val projectVersion = directChildText(root, "version")
            ?: directChildElement(root, "parent")?.let { directChildText(it, "version") }

        if (!projectGroupId.isNullOrBlank()) {
            props["project.groupId"] = projectGroupId
            props["pom.groupId"] = projectGroupId
        }
        if (!projectVersion.isNullOrBlank()) {
            props["project.version"] = projectVersion
            props["pom.version"] = projectVersion
        }

        val propertiesEl = directChildElement(root, "properties")
        if (propertiesEl != null) {
            val children = propertiesEl.childNodes
            for (i in 0 until children.length) {
                val propEl = children.item(i) as? Element ?: continue
                val value = propEl.textContent?.trim()
                if (!value.isNullOrEmpty()) {
                    props[propEl.tagName] = value
                }
            }
        }
        return props
    }

    private fun resolvePlaceholders(value: String?, props: Map<String, String>): String? {
        if (value == null) return null
        var resolved = value.trim()
        val regex = Regex("\\$\\{([^}]+)}")
        repeat(3) {
            resolved = regex.replace(resolved) { match ->
                props[match.groupValues[1]] ?: match.value
            }
        }
        return resolved
    }

    private fun directChildElement(parent: Element, tagName: String): Element? {
        val children = parent.childNodes
        for (i in 0 until children.length) {
            val child = children.item(i) as? Element ?: continue
            if (child.tagName == tagName) return child
        }
        return null
    }

    private fun directChildText(parent: Element, tagName: String): String? =
        directChildElement(parent, tagName)?.textContent?.trim()?.takeIf { it.isNotEmpty() }

    private fun classifyCompileJars(
        inventory: List<JarMetadata>,
        owners: Map<String, String>,
        rawEdges: Map<String, LinkedHashSet<String>>,
        evidence: EdgeEvidence,
        ignoredJars: Set<String>,
        classExcludes: ClassExcludes
    ): Map<String, String>? {
        val packages = compilePackages.get()
        val prefixes = CompilePackages.internalPrefixes(packages)
        if (prefixes.isEmpty()) return null

        val byId = inventory.associateBy { it.moduleId }
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
            for ((target, reference) in ApiReferenceScanner.scan(meta.file, owners, moduleId, classExcludes::excludes)) {
                val text = "public API of ${reference.fromClass.replace('/', '.')} uses ${reference.toClass.replace('/', '.')}"
                rawEdges.getValue(moduleId).add(target)
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

    private fun scanReferences(
        file: File,
        owners: Map<String, String>,
        selfModule: String,
        skipClass: (String) -> Boolean = { false }
    ): Map<String, List<Pair<String, String>>> {
        val found = java.util.TreeMap<String, MutableList<Pair<String, String>>>()
        var currentClass = ""
        val collectorRemapper = object : Remapper() {
            override fun map(internalName: String): String {
                val owner = owners[internalName]
                if (owner != null && owner != selfModule) {
                    val examples = found.getOrPut(owner) { ArrayList() }
                    val example = currentClass to internalName
                    if (examples.size < EdgeEvidence.MAX_EXAMPLES && example !in examples) examples.add(example)
                }
                return internalName
            }
        }
        val sink = object : ClassVisitor(Opcodes.ASM9) {
            override fun visitMethod(
                access: Int, name: String?, descriptor: String?, signature: String?, exceptions: Array<out String>?
            ): MethodVisitor = object : MethodVisitor(Opcodes.ASM9) {}

            override fun visitField(
                access: Int, name: String?, descriptor: String?, signature: String?, value: Any?
            ): FieldVisitor = object : FieldVisitor(Opcodes.ASM9) {}

            override fun visitRecordComponent(name: String?, descriptor: String?, signature: String?): RecordComponentVisitor =
                object : RecordComponentVisitor(Opcodes.ASM9) {}
        }
        val visitor = ClassRemapper(sink, collectorRemapper)

        JarFile(file).use { jar ->
            val entries = jar.entries()
            while (entries.hasMoreElements()) {
                val entry = entries.nextElement()
                if (!entry.isDirectory &&
                    entry.name.endsWith(".class") &&
                    !entry.name.startsWith("META-INF/") &&
                    entry.name != "module-info.class"
                ) {
                    currentClass = entry.name.removeSuffix(".class")
                    if (skipClass(currentClass)) continue
                    jar.getInputStream(entry).use { stream ->
                        ClassReader(stream).accept(visitor, ClassReader.SKIP_DEBUG or ClassReader.SKIP_FRAMES)
                    }
                }
            }
        }
        return found
    }

    private fun describeReference(
        fromClass: String,
        toClass: String,
        duplicateClassOwners: Map<String, List<String>>
    ): String {
        val base = "${fromClass.replace('/', '.')} references ${toClass.replace('/', '.')}"
        val owners = duplicateClassOwners[toClass] ?: return base
        return "$base (that class is defined in several jars: ${owners.joinToString()}; the first one is used)"
    }

    private fun breakCycles(
        graph: Map<String, LinkedHashSet<String>>
    ): Pair<Map<String, LinkedHashSet<String>>, List<BrokenCycle>> {
        val result = graph.mapValues { LinkedHashSet(it.value) }
        val broken = ArrayList<BrokenCycle>()
        val visited = HashSet<String>()
        val visiting = HashSet<String>()
        val path = ArrayList<String>()

        fun dfs(node: String) {
            visiting.add(node)
            path.add(node)
            val iterator = result[node]?.iterator()
            while (iterator != null && iterator.hasNext()) {
                val target = iterator.next()
                if (visiting.contains(target)) {
                    broken.add(BrokenCycle(path.subList(path.indexOf(target), path.size) + target))
                    iterator.remove()
                } else if (!visited.contains(target)) {
                    dfs(target)
                }
            }
            path.removeAt(path.lastIndex)
            visiting.remove(node)
            visited.add(node)
        }

        for (node in result.keys) {
            if (!visited.contains(node)) dfs(node)
        }
        return result to broken
    }

    private fun reportExcludes(classExcludes: ClassExcludes, jarExcludes: JarExcludes, ignoredJars: Set<String>) {
        if (ignoredJars.isNotEmpty()) {
            logger.lifecycle(
                "Vendor dependency check: {} jar(s) are left out by vendor.dependencyExcludeJars ({})",
                ignoredJars.size, ignoredJars.joinToString()
            )
        }
        classExcludes.unmatched().takeIf { it.isNotEmpty() }?.let {
            logger.warn(
                "vendor.dependencyExcludeClasses: no class in the selected jars matches {}",
                it.joinToString()
            )
        }
        jarExcludes.unmatched().takeIf { it.isNotEmpty() }?.let {
            logger.warn(
                "vendor.dependencyExcludeJars: no selected jar matches {} (the path is relative to the vendor directory)",
                it.joinToString()
            )
        }
    }

    private fun reportRedundantEdges(reduction: ReducedGraph, edges: Map<String, Set<String>>) {
        if (reduction.redundant.isEmpty()) return
        logger.lifecycle(
            "Vendor dependency tree: {} of {} direct dependencies are left out because another dependency of the same jar " +
                "already brings them in (--info lists them)",
            reduction.redundant.size, edges.values.sumOf { it.size }
        )
        reduction.redundant.forEach { logger.info("  {} -> {} is covered by {}", it.from, it.to, it.via) }
    }

    private fun reportBrokenCycles(cycles: List<BrokenCycle>, evidence: EdgeEvidence) {
        val shown = if (logger.isInfoEnabled) EdgeEvidence.MAX_EXAMPLES else 3
        for (cycle in cycles) {
            val message = StringBuilder()
            message.append("Breaking circular vendor JAR dependency: ${cycle.removedFrom} -> ${cycle.removedTo}\n")
            message.append("  cycle: ${cycle.path.joinToString(" -> ")}\n")
            for ((from, to) in cycle.edges) {
                val removed = from == cycle.removedFrom && to == cycle.removedTo
                message.append("  $from -> $to${if (removed) "   (removed from the module metadata)" else ""}\n")
                val examples = evidence.of(from, to)
                if (examples.isEmpty()) message.append("      (no recorded reason)\n")
                examples.take(shown).forEach { message.append("      $it\n") }
                if (examples.size > shown) message.append("      ... more examples with --info\n")
            }
            message.append("  All jars are still packaged; only this dependency declaration is dropped.")
            logger.warn(message.toString())
        }
    }

    private fun topologicalSort(dag: Map<String, Set<String>>): List<String> {
        val visited = HashSet<String>()
        val order = ArrayList<String>(dag.size)

        fun visit(node: String) {
            if (!visited.add(node)) return
            for (dep in dag[node].orEmpty()) {
                visit(dep)
            }
            order.add(node)
        }

        for (node in dag.keys) {
            visit(node)
        }
        return order
    }
}

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

internal data class BrokenCycle(val path: List<String>) {
    val removedFrom: String get() = path[path.size - 2]
    val removedTo: String get() = path.last()
    val edges: List<Pair<String, String>> get() = path.zipWithNext()
}
