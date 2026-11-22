/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 *
 * Copyright (c) 2026 Edgars Grinvalds <edgars.grinvalds@brain.lv>
 */

package lv.brain.gradle.distributor

import java.io.File
import java.util.concurrent.ConcurrentHashMap
import lv.brain.gradle.core.CategoryRule
import lv.brain.gradle.core.ClassExcludes
import lv.brain.gradle.core.GroupRule
import lv.brain.gradle.core.JarExcludes
import lv.brain.gradle.core.LicensePolicy
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
import org.gradle.api.tasks.LocalState
import org.gradle.api.tasks.Optional
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction

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

    /** Folders and files below [vendorDir] that are never scanned, see [VendorDistributorSettingsExtension.scanExcludes]. */
    @get:Input
    abstract val scanExcludes: ListProperty<String>

    /** The jars below [vendorDir] that are scanned; `ivy/`, `build/`, hidden folders and [scanExcludes] are left out. */
    @get:InputFiles
    @get:PathSensitive(PathSensitivity.RELATIVE)
    val vendorJars: FileTree
        get() = VendorJarScanner.jarTree(vendorDir, scanExcludes)

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

    /** Dependencies (`from->to` module names) added to the found ones. */
    @get:Input
    abstract val dependencyAdd: ListProperty<String>

    /** Found dependencies (`from->to` module names) that are dropped. */
    @get:Input
    abstract val dependencyRemove: ListProperty<String>

    /**
     * `off`, `warn` or `add`: whether the code of jars with a `pom.xml` is compared with the `pom.xml`, and whether the
     * vendor jars it uses but the `pom.xml` does not list are only reported or also added.
     */
    @get:Input
    abstract val pomCheck: Property<String>

    /**
     * `warn`, `fail` or `off`: what happens when vendor jars would break on the Java module path (one module name used
     * by several jars, or a package split between named modules).
     */
    @get:Input
    abstract val jpmsCheck: Property<String>

    /** `warn`, `fail` or `off`: what happens when vendor code uses classes that are in no vendor jar and not in the JDK. */
    @get:Input
    abstract val missingClasses: Property<String>

    /** Licence patterns a jar may have; empty means any licence that [licenseDeny] does not name. */
    @get:Input
    abstract val licenseAllow: ListProperty<String>

    /** Licence patterns no jar may have. */
    @get:Input
    abstract val licenseDeny: ListProperty<String>

    /** `warn`, `fail` or `off`: what happens to a jar without a known licence while a licence policy is set. */
    @get:Input
    abstract val licenseUnknown: Property<String>

    /**
     * `vendor` (default): only classes used by jars that are not known to be open source are reported as missing; those
     * only open-source jars use are usually their optional dependencies and are only counted. `all`: every class counts.
     */
    @get:Input
    abstract val missingClassesScope: Property<String>

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

    /** Remembers what each jar references, so a jar whose content did not change is not read again. */
    @get:LocalState
    abstract val scanCacheDir: DirectoryProperty

    init {
        scanCacheDir.convention(project.layout.buildDirectory.dir("vendor/scan-cache"))
        defaultOrg.convention("lv.brain.vendor")
        defaultVersion.convention("1.0.0")
        scanExcludes.convention(emptyList())
        fallbackCategory.convention("core")
        skipUnclassified.convention(false)
        compilePackages.convention(emptyList())
        dependencyExcludeClasses.convention(emptyList())
        dependencyExcludeJars.convention(emptyList())
        dependencyAdd.convention(emptyList())
        dependencyRemove.convention(emptyList())
        pomCheck.convention(POM_CHECK_OFF)
        missingClasses.convention(MISSING_WARN)
        jpmsCheck.convention(MISSING_WARN)
        missingClassesScope.convention(SCOPE_VENDOR)
        licenseAllow.convention(emptyList())
        licenseDeny.convention(emptyList())
        licenseUnknown.convention(MISSING_WARN)
    }

    /** Selects the jars, resolves their dependencies and writes [dependencyGraphFile]. */
    @TaskAction
    fun resolveDependencies() {
        val rootDir = vendorDir.get().asFile
        val rules = categoryRules.get()
        val report = ModuleGraphReport(logger)
        val selection = VendorJarScanner.selectJars(
            vendorRoot = rootDir,
            rules = rules,
            classifier = classifier,
            fallbackCategory = if (skipUnclassified.get()) null else fallbackCategory.get(),
            scanExcludes = scanExcludes.get()
        )
        report.selection(rules, selection, fallbackCategory.get())

        if (selection.jars.isEmpty()) {
            ModuleGraphJson.write(dependencyGraphFile.get().asFile, ModuleGraphJson.empty(selection.skipped.size, compilePackages.get()))
            return
        }

        val inventory = VendorInventory.build(selection, defaultOrg.get(), defaultVersion.get(), groupRules.get(), logger)
        val classExcludes = ClassExcludes(dependencyExcludeClasses.get())
        val jarExcludes = JarExcludes(dependencyExcludeJars.get(), rootDir.name)
        val ignoredJars: Set<String> = inventory
            .filter { jarExcludes.excludes(it.file.relativeTo(rootDir).invariantSeparatorsPath) }
            .mapTo(LinkedHashSet()) { it.moduleId }
        val checks = VendorJarChecks(inventory, ignoredJars, logger)
        checks.reportDefaultGroups(rootDir, defaultOrg.get())

        val cache = JarScanCache(scanCacheDir.orNull?.asFile)
        val references = ConcurrentHashMap<String, RawReferences>()
        fun referencesOf(meta: JarMetadata): RawReferences =
            references[meta.moduleId] ?: cache.references(meta.file, meta.contentKey, meta.definedClasses)
                .also { references.putIfAbsent(meta.moduleId, it) }

        val graph = ModuleGraphBuilder(inventory, ignoredJars, classExcludes, ::referencesOf, logger)
        inventory.parallelStream().forEach { referencesOf(it) }
        graph.reportSharedClasses()
        graph.collectEdges()

        val compileReasons = graph.classifyCompileJars(compilePackages.get())
        val addedByHand = graph.applyOverrides(dependencyAdd.get(), dependencyRemove.get())
        val pomFindings = if (pomCheck.get() != POM_CHECK_OFF) graph.checkPoms(pomCheck.get() == POM_CHECK_ADD, addedByHand) else null
        val licenses = checks.checkLicenses(LicensePolicy(licenseAllow.get(), licenseDeny.get(), licenseUnknown.get()))
        val jpms = if (jpmsCheck.get() != MISSING_OFF) checks.checkModulePath(jpmsCheck.get()) else null
        val missing = if (missingClasses.get() != MISSING_OFF) {
            checks.findMissingClasses(classExcludes, ::referencesOf, missingClasses.get(), missingClassesScope.get())
        } else null
        cache.prune()
        logger.info("Vendor scan cache: {} jar(s) read, {} reused", cache.scanned.get(), cache.reused.get())
        report.excludes(classExcludes, jarExcludes, ignoredJars)

        val (acyclicEdges, brokenCycles) = DependencyCycles.breakCycles(graph.edges, graph.strength)
        report.brokenCycles(brokenCycles, graph.evidence)
        val topologicalOrder = DependencyCycles.topologicalSort(acyclicEdges)
        val reduction = DependencyReduction.reduce(acyclicEdges, topologicalOrder)
        report.redundantEdges(reduction, acyclicEdges)
        val apiEdges: Map<String, LinkedHashSet<String>>? = compileReasons?.let { compile ->
            val contracted = DependencyReduction.contract(acyclicEdges, compile.keys)
            DependencyReduction.reduce(contracted, topologicalOrder.filter { it in compile }).edges
        }

        val modules = ModuleGraphJson.modules(
            ModuleGraphJson.Graph(topologicalOrder, inventory.associateBy { it.moduleId }, reduction.edges, apiEdges, compileReasons),
            graph, rootDir, ::referencesOf
        )
        ModuleGraphJson.write(
            dependencyGraphFile.get().asFile,
            mapOf(
                "schemaVersion" to 1,
                "moduleCount" to modules.size,
                "skippedJarCount" to selection.skipped.size,
                "topologicalOrder" to topologicalOrder,
                "compileSplit" to (compileReasons != null),
                "compilePackages" to compilePackages.get(),
                "dependencyExcludes" to mapOf(
                    "classes" to dependencyExcludeClasses.get(),
                    "jars" to dependencyExcludeJars.get(),
                    "ignoredJars" to ignoredJars.toList()
                ),
                "modules" to modules,
                "pomCheck" to pomFindings,
                "missingClasses" to missing,
                "jpms" to jpms,
                "licensePolicy" to licenses,
                "sharedClasses" to ModuleGraphJson.sharedClasses(graph.sharedClassGroups()),
                "redundantEdges" to reduction.redundant.map { mapOf("from" to it.from, "to" to it.to, "via" to it.via) },
                "brokenCycles" to ModuleGraphJson.brokenCycles(brokenCycles, graph.evidence)
            )
        )
    }
}
