/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 *
 * Copyright (c) 2026 Edgars Grinvalds <edgars.grinvalds@brain.lv>
 */

package lv.brain.gradle.consumer

import lv.brain.gradle.core.ConsumerVendorConfig
import lv.brain.gradle.core.GitExcludeSource
import lv.brain.gradle.core.GradleSupport
import lv.brain.gradle.core.VendorLayout
import lv.brain.gradle.core.VendorModuleLookup
import lv.brain.gradle.core.VendorModuleRef
import lv.brain.gradle.core.VendorRepositoryLayout
import lv.brain.gradle.core.VendorRepositoryLayout.addVendorIvyRepository
import lv.brain.gradle.core.VendorSignature
import lv.brain.gradle.core.VendorUnpackSource
import org.gradle.api.GradleException
import org.gradle.api.IsolatedAction
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.artifacts.dsl.RepositoryHandler
import org.gradle.api.configuration.BuildFeatures
import org.gradle.api.file.ConfigurableFileTree
import org.gradle.api.model.ObjectFactory
import org.gradle.api.initialization.Settings
import org.gradle.api.plugins.JavaBasePlugin
import org.gradle.api.plugins.JavaPlugin
import java.io.File
import java.net.URI
import javax.inject.Inject

internal data class VendorDistributionContext(
    val config: ConsumerVendorConfig,
    val vendorHomeDir: File,
    val onlyModules: List<String>? = null
) {
    val layout: VendorLayout by lazy { VendorLayout.read(vendorHomeDir) }
    val repositoryName: String get() = "VendorConsumerLocalIvy_" + (config.name?.let { "${it}_" } ?: "") + config.version
}

internal data class ResolvedVendorContext(
    val distributions: List<VendorDistributionContext>
) {
    val primary: VendorDistributionContext get() = distributions.first()
    val config: ConsumerVendorConfig get() = primary.config
    val vendorHomeDir: File get() = primary.vendorHomeDir
    val layout: VendorLayout get() = primary.layout
}

internal const val VENDOR_CONTEXT_KEY = "lv.brain.vendor.context"

/**
 * The `lv.brain.vendor-consumer` settings plugin: makes packaged vendor distributions available to every project.
 *
 * Apply it in `settings.gradle(.kts)` and configure it with `vendor.properties` (and an optional, git-excluded
 * `vendor.local.properties`). It downloads the distribution from `vendor.url` (or takes `vendor.localZip` /
 * `vendor.localDir`), unpacks it once per version, adds its Ivy repository to the build and applies
 * [VendorConsumerProjectPlugin] to every project, so `vendor.bundle(...)` and `vendor.module(...)` work everywhere.
 * Further distributions are listed in `vendor.distributions` and used with `vendor.from("<name>")`.
 */
class VendorConsumerSettingsPlugin @Inject constructor(
    private val buildFeatures: BuildFeatures
) : Plugin<Settings> {
    override fun apply(settings: Settings) {
        GradleSupport.requireSupported("lv.brain.vendor-consumer")
        val configs = ConsumerVendorConfig.loadAll(settings)
        val shared = configs.first()
        if (shared.gitExclude) GitExcludeSource.ensure(settings)

        val baseCacheDir = if (shared.cacheMode.equals("project", ignoreCase = true)) {
            File(settings.rootDir, ".vendor")
        } else {
            File(settings.gradle.gradleUserHomeDir, "caches/vendor-sdk")
        }

        val roots = configs.map { config ->
            val archiveName = config.localZipOverride?.name
                ?: config.distributionUrl?.replace("{version}", config.version)?.substringBefore('?')?.substringAfterLast('/')
            val variant = if (archiveName?.endsWith("-slim.zip", ignoreCase = true) == true) "-slim" else ""
            val folder = config.name?.let { File(baseCacheDir, it) } ?: baseCacheDir
            config to (config.localVendorDirOverride ?: File(folder, config.version + variant))
        }

        for ((config, root) in roots) {
            val remoteZipUrl = if (config.localVendorDirOverride == null && config.localZipOverride == null) {
                val prefix = config.name?.let { "vendor.$it." } ?: "vendor."
                val rawUrl = config.distributionUrl
                    ?: error("Either '${prefix}url', '${prefix}localZip', or '${prefix}localDir' must be configured.")
                URI(rawUrl.replace("{version}", config.version))
            } else null
            if (remoteZipUrl != null) settings.buildscript.repositories.addVendorArchiveRepositories(config, remoteZipUrl)
            ensureUnpacked(settings, config, root)
        }

        val onlyModules = if (roots.size > 1) VendorDistributionOverlap.check(roots) else null
        val contexts = roots.mapIndexed { index, (config, root) ->
            VendorDistributionContext(config.copy(authToken = null), root, onlyModules?.get(index))
        }
        val context = ResolvedVendorContext(contexts)
        settings.gradle.extensions.extraProperties.set(VENDOR_CONTEXT_KEY, context)

        registerVendorPlugins(settings, contexts)
        settings.dependencyResolutionManagement {
            repositories {
                contexts.forEach { addVendorIvyRepository(it.repositoryName, it.vendorHomeDir, it.onlyModules) }
            }
        }

        val prepare = PrepareConsumerProject(context)
        settings.gradle.lifecycle.beforeProject(prepare)

        if (!buildFeatures.isolatedProjects.active.get()) {
            settings.gradle.projectsLoaded {
                rootProject.allprojects { prepare.execute(this) }
            }
        }
    }
}

internal object VendorDistributionOverlap {
    @Suppress("UNCHECKED_CAST")
    private fun hashes(root: File): Map<String, String> {
        val graph = File(root, "module-dependencies.json")
        if (!graph.isFile) return emptyMap()
        val modules = ((groovy.json.JsonSlurper().parse(graph) as? Map<String, Any?>)?.get("modules") as? List<Map<String, Any?>>).orEmpty()
        return modules.mapNotNull { m ->
            val sha = m["sha256"] as? String ?: return@mapNotNull null
            "${m["groupId"]}:${m["artifactId"]}:${m["version"]}" to sha
        }.toMap()
    }

    fun check(roots: List<Pair<ConsumerVendorConfig, File>>): List<List<String>> {
        val owners = LinkedHashMap<String, MutableList<Pair<String, String?>>>()
        val perDistribution = roots.map { (config, root) ->
            val hashes = hashes(root)
            val modules = VendorRepositoryLayout.modules(root)
            modules.forEach { ref ->
                val gav = "${ref.group}:${ref.artifact}:${ref.version}"
                owners.getOrPut(gav) { ArrayList() }.add(config.displayName to hashes[gav])
            }
            modules.map { "${it.group}:${it.artifact}" }.distinct()
        }
        val conflicts = owners.filter { (_, list) ->
            list.size > 1 && (list.any { it.second == null } || list.map { it.second }.distinct().size > 1)
        }
        if (conflicts.isNotEmpty()) {
            throw GradleException(
                "Vendor distributions publish the same module with different content, so a build could get either one:\n" +
                    conflicts.entries.take(20).joinToString("\n") { (gav, list) -> "  $gav in ${list.joinToString { it.first }}" } +
                    (if (conflicts.size > 20) "\n  ..." else "") +
                    "\nBuild one of the distributions with its own vendor.defaultGroup (and set vendor.<name>.defaultGroup to the " +
                    "same value here), or give the jar distinct coordinates with a vendor.rule.<n> in its distributor."
            )
        }
        return perDistribution
    }
}

private fun RepositoryHandler.addVendorArchiveRepositories(config: ConsumerVendorConfig, remoteZipUrl: URI) {
    val baseUri = remoteZipUrl.resolve(".")
    val fileNamePattern = remoteZipUrl.path.substringAfterLast('/')
        .replace(config.version, "[revision]")
    val module = archiveModule(config)

    fun repository(suffix: String, pattern: String, artifactModule: String) = ivy {
        name = "VendorDistributionArchiveRepo" + (config.name?.let { "_$it" } ?: "") + suffix
        url = baseUri
        if (config.allowInsecureProtocol || remoteZipUrl.scheme.equals("http", ignoreCase = true)) {
            isAllowInsecureProtocol = true
        }
        patternLayout {
            artifact(pattern)
        }
        metadataSources {
            artifact()
        }
        if (!config.authToken.isNullOrBlank()) {
            credentials {
                username = "token"
                password = config.authToken
            }
            authentication {
                create("basic", org.gradle.authentication.http.BasicAuthentication::class.java)
            }
        }
        content {
            includeModule(ARCHIVE_GROUP, artifactModule)
        }
    }
    repository("", fileNamePattern, module)
    if (config.signingPublicKey != null) repository("_Signature", fileNamePattern + VendorSignature.SUFFIX, "$module-sig")
}

private const val ARCHIVE_GROUP = "lv.brain.vendor.dist"

private fun archiveModule(config: ConsumerVendorConfig) = config.name ?: "vendor-sdk"

private fun ensureUnpacked(settings: Settings, config: ConsumerVendorConfig, vendorRoot: File) {
    val prefix = config.name?.let { "vendor.$it." } ?: "vendor."
    val localDir = config.localVendorDirOverride
    if (localDir != null) {
        require(localDir.exists()) { "Configured ${prefix}localDir does not exist: $localDir" }
        val ivyDir = File(localDir, "ivy")
        require(ivyDir.exists()) { "Expected Ivy repository directory not found at: ${ivyDir.absolutePath}" }
        VendorLayout.requireCurrent(localDir)
        return
    }

    val localZip = config.localZipOverride
    if (localZip != null) {
        require(localZip.isFile) { "Configured ${prefix}localZip does not exist: $localZip" }
        VendorUnpackSource.ensure(
            settings, vendorRoot, localZip, config.sha256, File(localZip.path + VendorSignature.SUFFIX), config.signingPublicKey
        )
        return
    }

    if (VendorUnpackSource.isReady(settings, vendorRoot)) return
    val zip = resolveArchive(settings, config, "zip")
    val signature = config.signingPublicKey?.let {
        runCatching { resolveArchive(settings, config, "sig") }.getOrNull()
    }
    VendorUnpackSource.ensure(settings, vendorRoot, zip, config.sha256, signature, config.signingPublicKey)
}

private fun registerVendorPlugins(settings: Settings, contexts: List<VendorDistributionContext>) {
    val plugins = sortedMapOf<String, String>()
    val withPlugins = ArrayList<VendorDistributionContext>()
    for (context in contexts) {
        val found = VendorRepositoryLayout.plugins(context.vendorHomeDir)
        if (found.isEmpty()) continue
        withPlugins += context
        for ((id, coordinates) in found) {
            val previous = plugins.putIfAbsent(id, coordinates)
            if (previous != null && previous != coordinates) {
                throw GradleException("The Gradle plugin id '$id' comes from two vendor distributions ($previous and $coordinates).")
            }
        }
    }
    if (plugins.isEmpty()) return
    val pluginManagement = settings.pluginManagement
    val usedPortalByDefault = pluginManagement.repositories.isEmpty()
    withPlugins.forEach {
        pluginManagement.repositories.addVendorIvyRepository(it.repositoryName.replace("LocalIvy", "Plugins"), it.vendorHomeDir, it.onlyModules)
    }
    if (usedPortalByDefault) pluginManagement.repositories.gradlePluginPortal()
    pluginManagement.resolutionStrategy.eachPlugin {
        plugins[requested.id.id]?.let { useModule(it) }
    }
}

private fun resolveArchive(settings: Settings, config: ConsumerVendorConfig, kind: String): File {
    val module = archiveModule(config) + if (kind == "sig") "-sig" else ""
    val dep = settings.buildscript.dependencies.create("$ARCHIVE_GROUP:$module:${config.version}@$kind")
    return settings.buildscript.configurations.detachedConfiguration(dep).singleFile
}

internal class PrepareConsumerProject(
    private val context: ResolvedVendorContext
) : IsolatedAction<Project> {
    override fun execute(project: Project) {
        if (project.plugins.hasPlugin(VendorConsumerProjectPlugin::class.java)) return

        context.distributions.forEach {
            project.repositories.addVendorIvyRepository(it.repositoryName, it.vendorHomeDir, it.onlyModules)
        }

        project.extensions.extraProperties.set(VENDOR_CONTEXT_KEY, context)

        project.plugins.apply(VendorConsumerProjectPlugin::class.java)
    }
}

/**
 * One vendor distribution of a consumer build: builds its dependency notations and gives access to its files.
 *
 * The default distribution is used through `vendor` directly; a named one from `vendor.distributions` through
 * `vendor.from("<name>")`.
 */
open class VendorDistribution internal constructor(
    /** The configuration of this distribution. */
    val vendorConfig: ConsumerVendorConfig,
    /** The directory of the unpacked distribution (or its `localDir`). */
    val vendorHomeDir: File,
    private val objects: ObjectFactory
) {
    private val modules: List<VendorModuleRef> by lazy { VendorModuleLookup.list(vendorHomeDir) }

    /** The name from `vendor.distributions`, or `default`. */
    val name: String get() = vendorConfig.displayName

    /**
     * The dependency notation of a category bundle: every jar of that category with its dependencies.
     *
     * @param category the category name used by the distributor, e.g. `client`.
     * @return `<defaultGroup>.bundle:<category>:<version>`.
     */
    fun bundle(category: String): String =
        "${vendorConfig.defaultGroup}.bundle:$category:${vendorConfig.version}"

    /**
     * The dependency notation of one module of the distribution, found by name.
     *
     * Pass the group as well when the name alone is ambiguous. The version is the one the distribution publishes the
     * module in, which is not the version of the distribution.
     *
     * ```kotlin
     * vendor.module(name = "example-json")                            // Kotlin DSL
     * vendor.module(group = "org.example.json", name = "example-json")
     * vendor.module('example-json')                                   // Groovy DSL
     * vendor.module('org.example.json', 'example-json')
     * ```
     *
     * @param group the module group, or `null` to search by name only.
     * @param name the module name.
     * @return `<group>:<name>:<version>`.
     * @throws IllegalArgumentException when no module or more than one module matches.
     */
    @JvmOverloads
    fun module(group: String? = null, name: String): String =
        VendorModuleLookup.find(modules, group, name, bundleGroup = "${vendorConfig.defaultGroup}.bundle")

    /**
     * A file or folder of the unpacked distribution, for example a configuration file shipped by the vendor.
     *
     * @param path the path relative to the vendor folder, with `/` separators, e.g. `conf/app.properties`.
     * @throws IllegalArgumentException when the path leaves the vendor folder or does not exist in the distribution.
     */
    fun file(path: String): File = resolve(path)

    /**
     * The files below a folder of the unpacked distribution (configuration, native libraries, licenses), ready for
     * `from(...)` in a `Copy`, `Zip` or `distributions { }` block.
     *
     * ```kotlin
     * distributions { main { contents { from(vendor.files("conf")) { into("conf") } } } }
     * ```
     *
     * @param path the folder relative to the vendor folder, e.g. `native/linux-x64`.
     * @throws IllegalArgumentException when the path leaves the vendor folder or does not exist in the distribution.
     */
    fun files(path: String): ConfigurableFileTree = objects.fileTree().from(resolve(path))

    private fun resolve(path: String): File {
        val clean = path.trim().trim('/')
        val segments = clean.split('/')
        require(clean.isNotEmpty() && '\\' !in clean && segments.none { it == ".." || it == "." || it.isEmpty() } &&
            segments.first() != VendorRepositoryLayout.IVY_DIR) {
            "vendor.file(s)('$path'): use a path inside the vendor distribution such as 'conf' or 'native/linux-x64' " +
                "(no '..', not ivy/)."
        }
        val target = File(vendorHomeDir, clean)
        require(target.exists()) {
            "vendor.file(s)('$path'): $clean is not in the vendor distribution '$name' ($vendorHomeDir). The distributor " +
                "packages it only when vendor.zip.include matches it, and a -slim.zip holds only the files of vendor.slimZip.include."
        }
        return target
    }
}

/**
 * The `vendor` extension of each consumer project: builds dependency notations for the vendor distributions.
 *
 * ```kotlin
 * dependencies {
 *     implementation(vendor.bundle("client"))
 *     implementation(vendor.module(name = "example-json"))
 *     implementation(vendor.from("acme").bundle("client"))
 * }
 * ```
 */
open class VendorConsumerExtension @Inject constructor(private val objects: ObjectFactory) {
    private var distributions: Map<String?, VendorDistribution> = emptyMap()

    internal fun use(context: ResolvedVendorContext) {
        distributions = context.distributions.associate { it.config.name to VendorDistribution(it.config, it.vendorHomeDir, objects) }
    }

    private val default: VendorDistribution
        get() = distributions[null] ?: throw IllegalArgumentException(
            "This build has no default vendor distribution (vendor.version), only the named ones " +
                "${distributionNames.joinToString()}. Use vendor.from(\"${distributionNames.firstOrNull() ?: "name"}\").bundle(...)."
        )

    /** The names of `vendor.distributions`, without the default distribution. */
    val distributionNames: List<String> get() = distributions.keys.filterNotNull()

    /** The configuration of the default distribution (the first named one when there is no default). */
    val vendorConfig: ConsumerVendorConfig get() = (distributions[null] ?: distributions.values.first()).vendorConfig

    /** The directory of the unpacked default distribution (the first named one when there is no default). */
    val vendorHomeDir: File get() = (distributions[null] ?: distributions.values.first()).vendorHomeDir

    /**
     * A named distribution of `vendor.distributions`.
     *
     * @param name the name, e.g. `acme` for `vendor.acme.version`.
     * @throws IllegalArgumentException when the build has no distribution of that name.
     */
    fun from(name: String): VendorDistribution =
        distributions[name.lowercase()] ?: throw IllegalArgumentException(
            "vendor.from('$name'): there is no vendor distribution '$name'. Known: " +
                (distributionNames.ifEmpty { listOf("(none; list them in vendor.distributions)") }).joinToString()
        )

    /** [VendorDistribution.bundle] of the default distribution. */
    fun bundle(category: String): String = default.bundle(category)

    /** [VendorDistribution.module] of the default distribution. */
    @JvmOverloads
    fun module(group: String? = null, name: String): String = default.module(group, name)

    /** [VendorDistribution.file] of the default distribution. */
    fun file(path: String): File = default.file(path)

    /** [VendorDistribution.files] of the default distribution. */
    fun files(path: String): ConfigurableFileTree = default.files(path)
}

/**
 * The `lv.brain.vendor-consumer-project` plugin: adds the `vendor` extension ([VendorConsumerExtension]).
 *
 * The settings plugin applies it to every project already; apply it in a `plugins { }` block only to get the type-safe
 * `vendor` accessor in Kotlin build scripts. Also applies the `java` plugin when no Java plugin is applied, and registers
 * the `verify<SourceSet>VendorClasspath` tasks when the clash check is enabled.
 */
class VendorConsumerProjectPlugin : Plugin<Project> {
    override fun apply(project: Project) {
        if (!project.plugins.hasPlugin(JavaBasePlugin::class.java)) {
            project.plugins.apply(JavaPlugin::class.java)
        }

        val ext = project.extensions.findByType(VendorConsumerExtension::class.java)
            ?: project.extensions.create("vendor", VendorConsumerExtension::class.java)

        val ctx = (project.extensions.extraProperties.takeIf { it.has(VENDOR_CONTEXT_KEY) }
            ?: project.gradle.extensions.extraProperties.takeIf { it.has(VENDOR_CONTEXT_KEY) })
            ?.get(VENDOR_CONTEXT_KEY) as? ResolvedVendorContext
        if (ctx != null) {
            ext.use(ctx)
            VendorClasspathChecks.register(project, ctx)
        }
    }
}
