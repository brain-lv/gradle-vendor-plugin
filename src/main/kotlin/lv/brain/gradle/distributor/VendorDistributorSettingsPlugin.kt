/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 *
 * Copyright (c) 2026 Edgars Grinvalds <edgars.grinvalds@brain.lv>
 */

package lv.brain.gradle.distributor

import lv.brain.gradle.core.CategoryRule
import lv.brain.gradle.core.CompilePackages
import lv.brain.gradle.core.DependencyExcludes
import lv.brain.gradle.core.DependencyOverrides
import lv.brain.gradle.core.DistributorVendorConfig
import lv.brain.gradle.core.GitExcludeSource
import lv.brain.gradle.core.GradleSupport
import lv.brain.gradle.core.PathPatterns
import lv.brain.gradle.core.VendorRepositoryLayout.addVendorIvyRepository
import lv.brain.gradle.patcher.VendorPatcherExtension
import lv.brain.gradle.patcher.VendorPatcherPlugin
import org.gradle.api.Action
import org.gradle.api.GradleException
import org.gradle.api.IsolatedAction
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.initialization.ProjectDescriptor
import org.gradle.api.initialization.Settings
import org.gradle.api.model.ObjectFactory
import org.gradle.api.provider.ListProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.TaskProvider
import java.io.File
import javax.inject.Inject

/**
 * Selects the jars of one category inside `vendorDistributor { category("<name>") { ... } }`.
 *
 * Each category becomes a bundle that consumers add with `vendor.bundle("<name>")`.
 *
 * @property name the category name; it is also the name of the published bundle.
 */
open class CategorySpec(val name: String) {
    internal val patterns = mutableListOf<String>()

    /**
     * Adds jars to this category by Ant-style patterns relative to the vendor directory.
     *
     * A pattern that names a directory (`client/lib` or `client/lib/`) selects every jar below it, and a leading
     * `<vendor dir name>/` is accepted. A jar belongs to the first category whose pattern matches it; only jars matched
     * by some category are packaged, unless [VendorDistributorSettingsExtension.defaultCategory] is set.
     *
     * ```kotlin
     * category("client") { include("client/lib", "shared/client-api.jar") }
     * ```
     *
     * @param globs one or more Ant-style patterns (`*`, `**`, `?`).
     */
    fun include(vararg globs: String) {
        patterns.addAll(globs)
    }
}

/**
 * Selects the files of the vendor folder that go into a distribution ZIP, inside
 * `vendorDistributor { distributionZip { ... } }` or `slimDistributionZip { ... }`.
 *
 * The generated `ivy/` folder, the module graph and every jar of a category are always packaged.
 */
open class ZipContentSpec {
    internal val includes = mutableListOf<String>()
    internal val excludes = mutableListOf<String>()

    /**
     * Packages the files matching these Ant-style patterns, relative to the vendor directory. `*` alone means every file.
     *
     * @param patterns one or more patterns (`*`, `**`, `?`).
     */
    fun include(vararg patterns: String) {
        includes.addAll(patterns)
    }

    /**
     * Leaves out the files matching these Ant-style patterns, even when an include matches them.
     *
     * @param patterns one or more patterns (`*`, `**`, `?`).
     */
    fun exclude(vararg patterns: String) {
        excludes.addAll(patterns)
    }
}

/**
 * The `vendorDistributor { }` block in `settings.gradle(.kts)`, added by the `lv.brain.vendor-distributor` plugin.
 *
 * Every value starts with what `vendor.properties` says and can be overridden here.
 *
 * ```kotlin
 * vendorDistributor {
 *     slimJars.set(true)
 *     compilePackages.add("com.vendor")
 *     category("client") { include("client/lib") }
 *     category("server") { include("server/lib") }
 * }
 * ```
 */
open class VendorDistributorSettingsExtension @Inject constructor(objects: ObjectFactory) {
    /**
     * Whether smaller `-slim.jar` copies of the jars are generated for compilation.
     * The original jars are still used at runtime and in tests. Defaults to `vendor.slimJars`, otherwise `false`.
     */
    val slimJars: Property<Boolean> = objects.property(Boolean::class.java)
    /**
     * The directory that holds the vendor jars. Defaults to `vendor.dir`, otherwise `<root>/vendor`.
     * The generated Ivy repository is written to its `ivy/` subdirectory.
     */
    val vendorDir: Property<File> = objects.property(File::class.java)

    /**
     * Package prefixes (`com.vendor`) of the jars that consumers compile against.
     *
     * Every other jar is only on the runtime classpath, except jars that the public API of a compile jar needs.
     * When empty, every jar is a compile jar. Defaults to `vendor.compilePackages`.
     */
    val compilePackages: ListProperty<String> = objects.listProperty(String::class.java)

    /**
     * Classes and packages (`com.vendor.shaded`, `com.vendor.Legacy`, `com.*.internal.**`) that the check for
     * dependencies between the jars ignores: they create no dependency, and no dependency on the jar that holds them.
     *
     * Whether a jar is a compile jar is still decided by [compilePackages] alone. Defaults to `vendor.dependencyExcludeClasses`.
     */
    val dependencyExcludeClasses: ListProperty<String> = objects.listProperty(String::class.java)

    /**
     * Jars that the check for dependencies between the jars leaves out, as a prefix of their path in the vendor
     * directory (`client/lib/legacy-`) or an Ant-style pattern.
     *
     * They are still packaged and still belong to their category. Defaults to `vendor.dependencyExcludeJars`.
     */
    val dependencyExcludeJars: ListProperty<String> = objects.listProperty(String::class.java)

    /**
     * Category for jars that no `category { include(...) }` matches.
     *
     * When it is not set and at least one category is declared, such jars are left out of the distribution.
     * When no category is declared at all, every jar goes to `core`. Defaults to `vendor.defaultCategory`.
     */
    val defaultCategory: Property<String> = objects.property(String::class.java)

    /**
     * When `true`, the `lv.brain.vendor-patcher` plugin is applied to the root project and the numbered patches of
     * [patchesDir] are applied to a copy of the vendor folder (`build/vendor-patched/<name>`) before anything is
     * scanned or packaged. Defaults to `vendor.patcher`, otherwise `false`.
     */
    val patcher: Property<Boolean> = objects.property(Boolean::class.java)

    /** The folder with the numbered patch files. Defaults to `vendor.patchesDir`, otherwise `<root>/internal-patches`. */
    val patchesDir: Property<File> = objects.property(File::class.java)

    /**
     * Folders and files of the vendor directory that are never scanned or packaged, as Ant-style patterns or folder
     * names (`tools`, `**` + `/test-data`). The generated `ivy/`, a top-level `build/` and hidden folders such as
     * `.gradle` are always left out. Defaults to `vendor.scanExcludes`.
     */
    val scanExcludes: ListProperty<String> = objects.listProperty(String::class.java)

    /**
     * When `true`, `packageVendorSlimDistribution` also builds `vendor-sdk-<version>-slim.zip`, which holds only the
     * jars of a category, the generated `ivy/` folder and the files of [slimDistributionZip].
     * Defaults to `vendor.slimZip`, otherwise `false`.
     */
    val slimZip: Property<Boolean> = objects.property(Boolean::class.java)

    /**
     * Dependencies added to the ones the plugin finds, as `from->to` with the module names of
     * `module-dependencies.json` (`app-core->commons-lite`). Defaults to `vendor.dependencyAdd`.
     */
    val dependencyAdd: ListProperty<String> = objects.listProperty(String::class.java)

    /**
     * Found dependencies that are dropped, as `from->to` with the module names of `module-dependencies.json`.
     * Defaults to `vendor.dependencyRemove`.
     */
    val dependencyRemove: ListProperty<String> = objects.listProperty(String::class.java)

    /**
     * Whether the code of jars that carry a `pom.xml` is compared with the `pom.xml`: `off`, `warn` (names each vendor
     * jar their code uses but their `pom.xml` does not list) or `add` (also adds those dependencies).
     * `true` and `false` mean `warn` and `off`. Defaults to `vendor.pomCheck`, otherwise `off`.
     */
    val pomCheck: Property<String> = objects.property(String::class.java)

    /**
     * What happens when vendor code uses classes that are in no vendor jar and not in the JDK: `warn`, `fail` or `off`.
     * Defaults to `vendor.missingClasses`, otherwise `warn`.
     */
    val missingClasses: Property<String> = objects.property(String::class.java)

    /**
     * What happens when vendor jars would break on the Java module path (one module name used by several jars, or a
     * package split between named modules): `warn`, `fail` or `off`. Defaults to `vendor.jpmsCheck`, otherwise `warn`.
     */
    val jpmsCheck: Property<String> = objects.property(String::class.java)
    internal val zipContent = ZipContentSpec()
    internal val slimZipContent = ZipContentSpec()
    internal val rules = mutableListOf<CategoryRule>()
    internal var customClassifier: CategoryClassifier? = null

    /**
     * Declares a category and selects its jars.
     *
     * ```kotlin
     * category("client") { include("client/lib") }      // Kotlin DSL
     * category('client') { include 'client/lib' }        // Groovy DSL
     * ```
     *
     * Can be called several times; categories are matched in the order they are declared.
     *
     * @param name the category name, used as the bundle name (`vendor.bundle("<name>")`).
     * @param configure selects the jars of the category, see [CategorySpec.include].
     */
    fun category(name: String, configure: Action<in CategorySpec>) {
        val spec = CategorySpec(name)
        configure.execute(spec)
        rules.add(CategoryRule(name, spec.patterns.toList()))
    }

    /**
     * Selects the files of the vendor folder packaged into `vendor-sdk-<version>.zip`. Without includes every file is
     * packaged; defaults to `vendor.zip.include` and `vendor.zip.exclude`.
     *
     * ```kotlin
     * distributionZip { include("client", "server", "docs"); exclude("docs/internal") }
     * ```
     *
     * @param configure selects the files, see [ZipContentSpec].
     */
    fun distributionZip(configure: Action<in ZipContentSpec>) {
        configure.execute(zipContent)
    }

    /**
     * Selects further files packaged into `vendor-sdk-<version>-slim.zip` (see [slimZip]); without includes it holds no
     * other files. Defaults to `vendor.slimZip.include` and `vendor.slimZip.exclude`.
     *
     * @param configure selects the files, see [ZipContentSpec].
     */
    fun slimDistributionZip(configure: Action<in ZipContentSpec>) {
        configure.execute(slimZipContent)
    }

    /**
     * Assigns categories by code instead of (or in addition to) [category] includes.
     *
     * The function gets the path of each jar relative to the vendor directory and the jar file, and returns the
     * category name, or `null` to leave the jar to the include patterns. A build that uses a classifier cannot be stored
     * in the configuration cache, and `resolveVendorModules` then always runs.
     *
     * @param fn the classifier, see [CategoryClassifier].
     */
    fun classifier(fn: CategoryClassifier) {
        customClassifier = fn
    }
}

/**
 * The `lv.brain.vendor-distributor` settings plugin: packages a folder of vendor jars as a versioned distribution.
 *
 * Apply it in `settings.gradle(.kts)` and configure it with `vendor.properties` and the `vendorDistributor { }` block
 * ([VendorDistributorSettingsExtension]). It adds these tasks to the root project:
 *
 * - `resolveVendorModules` selects jars by the category includes and resolves the dependencies between them
 * - `generateVendorIvyRepo` writes the Ivy repository (module metadata, slim jars, bundles) to `<vendorDir>/ivy`
 * - `packageVendorDistribution` runs both and zips the result to `build/distributions/vendor-sdk-<version>.zip`
 *
 * Nothing is scanned or generated during configuration or IDE sync; all work happens when a task runs.
 */
class VendorDistributorSettingsPlugin : Plugin<Settings> {
    override fun apply(settings: Settings) {
        GradleSupport.requireSupported("lv.brain.vendor-distributor")
        val baseConfig = DistributorVendorConfig.load(settings)
        if (baseConfig.gitExclude) GitExcludeSource.ensure(settings)

        val dslExt = settings.extensions.create(
            "vendorDistributor",
            VendorDistributorSettingsExtension::class.java
        )
        dslExt.slimJars.convention(baseConfig.slimJars)
        dslExt.vendorDir.convention(baseConfig.vendorDir)
        dslExt.compilePackages.convention(baseConfig.compilePackages)
        dslExt.dependencyExcludeClasses.convention(baseConfig.dependencyExcludeClasses)
        dslExt.dependencyExcludeJars.convention(baseConfig.dependencyExcludeJars)
        if (baseConfig.defaultCategoryExplicit) dslExt.defaultCategory.convention(baseConfig.defaultCategory)
        dslExt.patcher.convention(baseConfig.patcher)
        dslExt.patchesDir.convention(baseConfig.patchesDir)
        dslExt.scanExcludes.convention(baseConfig.scanExcludes)
        dslExt.slimZip.convention(baseConfig.slimZip)
        dslExt.dependencyAdd.convention(baseConfig.dependencyAdd)
        dslExt.dependencyRemove.convention(baseConfig.dependencyRemove)
        dslExt.pomCheck.convention(baseConfig.pomCheck)
        dslExt.missingClasses.convention(baseConfig.missingClasses)
        dslExt.jpmsCheck.convention(baseConfig.jpmsCheck)

        settings.gradle.settingsEvaluated {
            rejectPatternProjects(settings)

            val sourceVendorDir = dslExt.vendorDir.get()
            val patcher = dslExt.patcher.get()
            val resolvedVendorDir = if (patcher) {
                File(settings.rootDir, "build/vendor-patched/${sourceVendorDir.name}")
            } else {
                sourceVendorDir
            }
            val explicitDefaultCategory = dslExt.defaultCategory.orNull

            val effectiveConfig = baseConfig.copy(
                slimJars = dslExt.slimJars.get(),
                compilePackages = dslExt.compilePackages.get().mapNotNull { CompilePackages.normalize(it) }.distinct(),
                dependencyExcludeClasses = dslExt.dependencyExcludeClasses.get().mapNotNull { DependencyExcludes.normalizeClass(it) }.distinct(),
                dependencyExcludeJars = dslExt.dependencyExcludeJars.get().mapNotNull { DependencyExcludes.normalizeJar(it) }.distinct(),
                vendorDir = resolvedVendorDir,
                defaultCategory = explicitDefaultCategory ?: baseConfig.defaultCategory,
                defaultCategoryExplicit = explicitDefaultCategory != null,
                categoryRules = dslExt.rules + baseConfig.categoryRules,
                patcher = patcher,
                patchesDir = dslExt.patchesDir.get(),
                patchSourceDir = if (patcher) sourceVendorDir else null,
                scanExcludes = dslExt.scanExcludes.get().map { PathPatterns.normalize(it) }.filter { it.isNotEmpty() }.distinct(),
                zipInclude = dslExt.zipContent.includes.toList().ifEmpty { baseConfig.zipInclude },
                zipExclude = dslExt.zipContent.excludes.toList().ifEmpty { baseConfig.zipExclude },
                slimZip = dslExt.slimZip.get(),
                slimZipInclude = dslExt.slimZipContent.includes.toList().ifEmpty { baseConfig.slimZipInclude },
                slimZipExclude = dslExt.slimZipContent.excludes.toList().ifEmpty { baseConfig.slimZipExclude },
                dependencyAdd = dslExt.dependencyAdd.get().map { DependencyOverrides.normalize(it, "dependencyAdd") }.distinct(),
                dependencyRemove = dslExt.dependencyRemove.get().map { DependencyOverrides.normalize(it, "dependencyRemove") }.distinct(),
                pomCheck = DistributorVendorConfig.pomCheckMode(dslExt.pomCheck.get(), "pomCheck"),
                missingClasses = DistributorVendorConfig.missingClassesMode(dslExt.missingClasses.get(), "missingClasses"),
                jpmsCheck = DistributorVendorConfig.missingClassesMode(dslExt.jpmsCheck.get(), "jpmsCheck")
            )

            settings.dependencyResolutionManagement {
                repositories {
                    addVendorIvyRepository(DISTRIBUTOR_REPOSITORY_NAME, resolvedVendorDir)
                }
            }

            settings.gradle.lifecycle.beforeProject(PrepareDistributorProject(effectiveConfig, dslExt.customClassifier))
        }
    }
}

private const val DISTRIBUTOR_REPOSITORY_NAME = "VendorDistributorLocalIvy"

internal class PrepareDistributorProject(
    private val config: DistributorVendorConfig,
    private val classifier: CategoryClassifier?
) : IsolatedAction<Project> {
    override fun execute(project: Project) {
        project.repositories.addVendorIvyRepository(DISTRIBUTOR_REPOSITORY_NAME, config.vendorDir)

        project.plugins.apply(VendorDistributorProjectPlugin::class.java)
        project.extensions.configure(VendorDistributorProjectExtension::class.java) {
            this.vendorConfig = config
            this.customClassifier = classifier
        }

        if (project.path == ":" && config.slimZip) VendorDistributorProjectPlugin.registerSlimZip(project)

        val patchSource = config.patchSourceDir
        if (project.path == ":" && config.patcher && patchSource != null) {
            project.plugins.apply(VendorPatcherPlugin::class.java)
            project.extensions.configure(VendorPatcherExtension::class.java) {
                sourceDir.set(patchSource)
                config.patchesDir?.let { patchesDir.set(it) }
                outputDir.set(config.vendorDir)
            }
            listOfNotNull(
                "resolveVendorModules", "generateVendorIvyRepo", "packageVendorDistribution",
                SLIM_ZIP_TASK.takeIf { config.slimZip }
            ).forEach { name ->
                project.tasks.named(name) { dependsOn("applyVendorPatches") }
            }
        }
    }
}

/**
 * The `vendorDistributor` extension of each project in a distributor build, set up by the settings plugin.
 *
 * Gives build scripts read access to the effective configuration and the bundle coordinates.
 */
open class VendorDistributorProjectExtension {
    /** The effective configuration: `vendor.properties` merged with the `vendorDistributor { }` block. */
    lateinit var vendorConfig: DistributorVendorConfig
    /** The classifier set with [VendorDistributorSettingsExtension.classifier], or `null`. */
    var customClassifier: CategoryClassifier? = null

    /** `true` when jars that match no category include are left out of the distribution. */
    fun skipsUnclassifiedJars(): Boolean =
        vendorConfig.categoryRules.isNotEmpty() && !vendorConfig.defaultCategoryExplicit

    /** The categories known from the configuration alone, without reading any jar. */
    fun declaredCategories(): List<String> =
        (vendorConfig.categoryRules.map { it.category } +
            if (skipsUnclassifiedJars()) emptyList() else listOf(vendorConfig.defaultCategory)).distinct()

    /**
     * The dependency notation of a category bundle, `<defaultGroup>.bundle:<category>:<version>`.
     *
     * @param category the category name.
     */
    fun bundle(category: String): String =
        "${vendorConfig.defaultGroup}.bundle:$category:${vendorConfig.version}"
}

/**
 * Applied to every project of a distributor build by [VendorDistributorSettingsPlugin]; do not apply it yourself.
 *
 * Adds the [VendorDistributorProjectExtension], a `vendor<Category>` configuration per declared category, and, in the
 * root project, the `resolveVendorModules`, `generateVendorIvyRepo` and `packageVendorDistribution` tasks.
 */
class VendorDistributorProjectPlugin : Plugin<Project> {
    override fun apply(project: Project) {
        val ext = project.extensions.create("vendorDistributor", VendorDistributorProjectExtension::class.java)

        project.afterEvaluate {
            ext.declaredCategories().forEach { category ->
                val cfgName = "vendor" + category.split('-').joinToString("") { part ->
                    part.replaceFirstChar { it.uppercase() }
                }
                if (configurations.findByName(cfgName) == null) {
                    val cfg = configurations.create(cfgName)
                    dependencies.add(cfg.name, ext.bundle(category))
                }
            }
        }

        if (project.path == ":") registerTasks(project, ext)
    }

    private fun registerTasks(project: Project, ext: VendorDistributorProjectExtension) {
        val vendorDir = project.layout.dir(project.provider { ext.vendorConfig.vendorDir })
        val ivyDir = project.layout.dir(project.provider { File(ext.vendorConfig.vendorDir, VendorJarScanner.IVY_DIR_NAME) })
        val graphFile = project.layout.buildDirectory.file("vendor/module-dependencies.json")

        val resolve = project.tasks.register("resolveVendorModules", ResolveModuleDependenciesTask::class.java) {
            group = "vendor"
            description = "Selects vendor JARs by the category includes and resolves the dependencies between them."
            this.vendorDir.set(vendorDir)
            defaultOrg.set(project.provider { ext.vendorConfig.defaultGroup })
            defaultVersion.set(project.provider { ext.vendorConfig.version })
            categoryRules.set(project.provider { ext.vendorConfig.categoryRules })
            groupRules.set(project.provider { ext.vendorConfig.groupRules })
            compilePackages.set(project.provider { ext.vendorConfig.compilePackages })
            dependencyExcludeClasses.set(project.provider { ext.vendorConfig.dependencyExcludeClasses })
            dependencyExcludeJars.set(project.provider { ext.vendorConfig.dependencyExcludeJars })
            scanExcludes.set(project.provider { ext.vendorConfig.scanExcludes })
            dependencyAdd.set(project.provider { ext.vendorConfig.dependencyAdd })
            dependencyRemove.set(project.provider { ext.vendorConfig.dependencyRemove })
            pomCheck.set(project.provider { ext.vendorConfig.pomCheck })
            missingClasses.set(project.provider { ext.vendorConfig.missingClasses })
            missingClassesScope.set(project.provider { ext.vendorConfig.missingClassesScope })
            licenseAllow.set(project.provider { ext.vendorConfig.licensesAllow })
            licenseDeny.set(project.provider { ext.vendorConfig.licensesDeny })
            licenseUnknown.set(project.provider { ext.vendorConfig.licensesUnknown })
            jpmsCheck.set(project.provider { ext.vendorConfig.jpmsCheck })
            fallbackCategory.set(project.provider { ext.vendorConfig.defaultCategory })
            skipUnclassified.set(project.provider { ext.skipsUnclassifiedJars() })
            dependencyGraphFile.set(graphFile)

            ext.customClassifier?.let { custom ->
                classifier = custom
                outputs.upToDateWhen { false }
                outputs.cacheIf { false }
                notCompatibleWithConfigurationCache("vendorDistributor { classifier { ... } } is a lambda")
            }
        }

        val generate = project.tasks.register("generateVendorIvyRepo", GenerateVendorIvyRepoTask::class.java) {
            group = "vendor"
            description = "Generates the virtual Ivy repository (module metadata, slim JARs, category bundles) in the vendor directory."
            this.vendorDir.set(vendorDir)
            dependencyGraphFile.set(resolve.flatMap { it.dependencyGraphFile })
            versionString.set(project.provider { ext.vendorConfig.version })
            bundleGroup.set(project.provider { "${ext.vendorConfig.defaultGroup}.bundle" })
            slimJars.set(project.provider { ext.vendorConfig.slimJars })
            scanExcludes.set(project.provider { ext.vendorConfig.scanExcludes })
            this.ivyDir.set(ivyDir)
        }

        project.tasks.register(SBOM_TASK, GenerateVendorSbomTask::class.java) {
            group = "vendor"
            description = "Writes a CycloneDX bill of materials of the vendor jars (build/vendor/vendor-sbom.cdx.json)."
            dependencyGraphFile.set(resolve.flatMap { it.dependencyGraphFile })
            versionString.set(project.provider { ext.vendorConfig.version })
            defaultGroup.set(project.provider { ext.vendorConfig.defaultGroup })
            sbomFile.set(project.layout.buildDirectory.file("vendor/${VendorSbom.FILE_NAME}"))
        }

        project.tasks.register("vendorReport", GenerateVendorReportTask::class.java) {
            group = "vendor"
            description = "Writes an HTML page of the vendor module graph (build/reports/vendor/index.html)."
            dependencyGraphFile.set(resolve.flatMap { it.dependencyGraphFile })
            versionString.set(project.provider { ext.vendorConfig.version })
            defaultGroup.set(project.provider { ext.vendorConfig.defaultGroup })
            reportFile.set(project.layout.buildDirectory.file("reports/vendor/index.html"))
        }

        project.tasks.register("packageVendorDistribution", PackageVendorZipTask::class.java) {
            group = "vendor"
            description = "Packages the vendor folder and the generated ivy/ metadata into a distribution ZIP."
            wireZip(this, project, ext, resolve, generate, slim = false)
        }

        val export = project.tasks.register("exportVendorMavenRepository", ExportVendorMavenRepositoryTask::class.java) {
            group = "vendor"
            description = "Writes every vendor module and bundle as a Maven repository (build/vendor-maven-repo)."
            this.vendorDir.set(vendorDir)
            scanExcludes.set(project.provider { ext.vendorConfig.scanExcludes })
            this.ivyDir.set(generate.flatMap { it.ivyDir })
            dependencyGraphFile.set(resolve.flatMap { it.dependencyGraphFile })
            versionString.set(project.provider { ext.vendorConfig.version })
            bundleGroup.set(project.provider { "${ext.vendorConfig.defaultGroup}.bundle" })
            outputDir.set(project.layout.buildDirectory.dir("vendor-maven-repo"))
        }

        project.tasks.register("publishVendorMavenRepository", PublishVendorMavenRepositoryTask::class.java) {
            group = "vendor"
            description = "Uploads the Maven repository of exportVendorMavenRepository to vendor.publish.url."
            repositoryDir.set(export.flatMap { it.outputDir })
            url.set(project.provider { ext.vendorConfig.publishUrl })
            username.set(project.provider { ext.vendorConfig.publishUsername })
            password.set(
                project.providers.gradleProperty("vendor.publish.password")
                    .orElse(project.providers.environmentVariable("VENDOR_PUBLISH_PASSWORD"))
            )
        }

        project.tasks.register("verifyVendorAgainstMaven", VerifyVendorAgainstMavenTask::class.java) {
            group = "vendor"
            description = "Checks the vendor jars and their dependencies against a Maven repository (Maven Central by default)."
            dependencyGraphFile.set(resolve.flatMap { it.dependencyGraphFile })
            this.vendorDir.set(vendorDir)
            repositoryUrl.set(project.provider { ext.vendorConfig.mavenCheckUrl })
            defaultGroup.set(project.provider { ext.vendorConfig.defaultGroup })
            failOn.set(project.provider { ext.vendorConfig.mavenCheckFailOn })
            licenseAllow.set(project.provider { ext.vendorConfig.licensesAllow })
            licenseDeny.set(project.provider { ext.vendorConfig.licensesDeny })
            reportDir.set(project.layout.buildDirectory.dir("reports/vendor-maven-check"))
        }

        project.tasks.register("generateVendorSigningKey", GenerateVendorSigningKeyTask::class.java) {
            group = "vendor"
            description = "Creates an Ed25519 key pair for signing the distribution (build/vendor-signing)."
            outputDir.set(project.layout.buildDirectory.dir("vendor-signing"))
        }

        project.tasks.register("diffVendorDistribution", DiffVendorDistributionTask::class.java) {
            group = "vendor"
            description = "Compares the distribution with an earlier one (--baseline=<zip>); report in build/reports/vendor-diff."
            this.vendorDir.set(vendorDir)
            scanExcludes.set(project.provider { ext.vendorConfig.scanExcludes })
            dependencyGraphFile.set(resolve.flatMap { it.dependencyGraphFile })
            baseline.convention(project.layout.file(project.provider { ext.vendorConfig.diffBaseline }))
            reportDir.set(project.layout.buildDirectory.dir("reports/vendor-diff"))
        }
    }

    internal companion object {
        fun registerSlimZip(project: Project) {
            val ext = project.extensions.getByType(VendorDistributorProjectExtension::class.java)
            val resolve = project.tasks.named("resolveVendorModules", ResolveModuleDependenciesTask::class.java)
            val generate = project.tasks.named("generateVendorIvyRepo", GenerateVendorIvyRepoTask::class.java)
            val slim = project.tasks.register(SLIM_ZIP_TASK, PackageVendorZipTask::class.java) {
                group = "vendor"
                description = "Packages only the vendor JARs of the categories and the generated ivy/ metadata into a slim distribution ZIP."
                wireZip(this, project, ext, resolve, generate, slim = true)
            }
            project.tasks.named("packageVendorDistribution") { dependsOn(slim) }
        }

        private fun wireZip(
            task: PackageVendorZipTask,
            project: Project,
            ext: VendorDistributorProjectExtension,
            resolve: TaskProvider<ResolveModuleDependenciesTask>,
            generate: TaskProvider<GenerateVendorIvyRepoTask>,
            slim: Boolean
        ) {
            task.vendorDir.set(project.layout.dir(project.provider { ext.vendorConfig.vendorDir }))
            task.scanExcludes.set(project.provider { ext.vendorConfig.scanExcludes })
            task.includes.set(project.provider { if (slim) ext.vendorConfig.slimZipInclude else ext.vendorConfig.zipInclude })
            task.excludes.set(project.provider { if (slim) ext.vendorConfig.slimZipExclude else ext.vendorConfig.zipExclude })
            task.ivyDir.set(generate.flatMap { it.ivyDir })
            task.dependencyGraphFile.set(resolve.flatMap { it.dependencyGraphFile })
            task.versionString.set(project.provider { ext.vendorConfig.version })
            task.sbomFile.set(project.tasks.named(SBOM_TASK, GenerateVendorSbomTask::class.java).flatMap { it.sbomFile })
            task.signingKey.set(
                project.providers.environmentVariable("VENDOR_SIGNING_KEY").orElse(
                    project.providers.fileContents(project.layout.file(project.provider { ext.vendorConfig.signingKeyFile })).asText
                )
            )
            val suffix = if (slim) "-slim" else ""
            task.outputZip.set(
                project.layout.buildDirectory.file(project.provider { "distributions/vendor-sdk-${ext.vendorConfig.version}$suffix.zip" })
            )
        }
    }
}

internal const val SLIM_ZIP_TASK = "packageVendorSlimDistribution"

internal const val SBOM_TASK = "generateVendorSbom"

private const val PATTERN_CHARS = "*?[]{}"

private fun ProjectDescriptor.descendants(): Sequence<ProjectDescriptor> =
    children.asSequence().flatMap { sequenceOf(it) + it.descendants() }

private fun rejectPatternProjects(settings: Settings) {
    val offenders = settings.rootProject.descendants()
        .filter { project -> project.name.any { it in PATTERN_CHARS } }
        .map { it.name }
        .toList()
    if (offenders.isEmpty()) return

    val example = offenders.first()
    throw GradleException(
        "The settings script calls include(...) with vendor file pattern(s) outside a category, which makes Gradle " +
            "create project(s) ${offenders.joinToString { "':$it'" }} instead of selecting jars.\n\n" +
            "Vendor patterns belong inside a category.\n" +
            "  Kotlin DSL (settings.gradle.kts):\n" +
            "    vendorDistributor {\n" +
            "        category(\"<name>\") { include(\"$example\") }\n" +
            "    }\n" +
            "  Groovy DSL (settings.gradle):\n" +
            "    vendorDistributor {\n" +
            "        category('<name>') { include '$example' }\n" +
            "    }\n" +
            "  or in vendor.properties:  vendor.category.<name>=$example\n\n" +
            "Move the include(...) call into a category block, or remove it."
    )
}
