/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 *
 * Copyright (c) 2026 brain-lv (https://github.com/brain-lv)
 */

package lv.brain.gradle.consumer

import lv.brain.gradle.core.ConsumerVendorConfig
import lv.brain.gradle.core.GitExcludeManager
import lv.brain.gradle.core.GradleSupport
import lv.brain.gradle.core.VendorLayout
import lv.brain.gradle.core.VendorModuleLookup
import lv.brain.gradle.core.VendorModuleRef
import lv.brain.gradle.core.VendorRepositoryLayout.addVendorIvyRepository
import lv.brain.gradle.core.ZipExtractor
import org.gradle.api.IsolatedAction
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.artifacts.dsl.RepositoryHandler
import org.gradle.api.configuration.BuildFeatures
import org.gradle.api.initialization.Settings
import org.gradle.api.plugins.JavaBasePlugin
import org.gradle.api.plugins.JavaPlugin
import java.io.File
import java.net.URI
import javax.inject.Inject

internal data class ResolvedVendorContext(
    val config: ConsumerVendorConfig,
    val vendorHomeDir: File
) {
    val layout: VendorLayout by lazy { VendorLayout.read(vendorHomeDir) }
}

internal const val VENDOR_CONTEXT_KEY = "lv.brain.vendor.context"

/**
 * The `lv.brain.vendor-consumer` settings plugin: makes a packaged vendor distribution available to every project.
 *
 * Apply it in `settings.gradle(.kts)` and configure it with `vendor.properties` (and an optional, git-excluded
 * `vendor.local.properties`). It downloads the distribution from `vendor.url` (or takes `vendor.localZip` /
 * `vendor.localDir`), unpacks it once per version, adds its Ivy repository to the build and applies
 * [VendorConsumerProjectPlugin] to every project, so `vendor.bundle(...)` and `vendor.module(...)` work everywhere.
 */
class VendorConsumerSettingsPlugin @Inject constructor(
    private val buildFeatures: BuildFeatures
) : Plugin<Settings> {
    override fun apply(settings: Settings) {
        GradleSupport.requireSupported("lv.brain.vendor-consumer")
        val config = ConsumerVendorConfig.load(settings)
        if (config.gitExclude) GitExcludeManager.ensureExcluded(settings.rootDir)

        val baseCacheDir = if (config.cacheMode.equals("project", ignoreCase = true)) {
            File(settings.rootDir, ".vendor")
        } else {
            File(settings.gradle.gradleUserHomeDir, "caches/vendor-sdk")
        }

        val resolvedVendorRoot: File = config.localVendorDirOverride
            ?: File(baseCacheDir, config.version)

        settings.gradle.extensions.extraProperties.set(
            VENDOR_CONTEXT_KEY,
            ResolvedVendorContext(config, resolvedVendorRoot)
        )

        val remoteZipUrl = if (config.localVendorDirOverride == null && config.localZipOverride == null) {
            val rawUrl = config.distributionUrl
                ?: error("Either 'vendor.url', 'vendor.localZip', or 'vendor.localDir' must be configured.")
            URI(rawUrl.replace("{version}", config.version))
        } else null

        if (remoteZipUrl != null) {
            settings.buildscript.repositories.addVendorArchiveRepository(config, remoteZipUrl)
        }
        ensureUnpacked(settings, config, resolvedVendorRoot)

        settings.dependencyResolutionManagement {
            repositories {
                addVendorIvyRepository("VendorConsumerLocalIvy_${config.version}", resolvedVendorRoot)
            }
        }

        val prepare = PrepareConsumerProject(config.copy(authToken = null), resolvedVendorRoot)
        settings.gradle.lifecycle.beforeProject(prepare)

        if (!buildFeatures.isolatedProjects.active.get()) {
            settings.gradle.projectsLoaded {
                rootProject.allprojects { prepare.execute(this) }
            }
        }
    }
}

private fun RepositoryHandler.addVendorArchiveRepository(config: ConsumerVendorConfig, remoteZipUrl: URI) {
    val baseUri = remoteZipUrl.resolve(".")
    val fileNamePattern = remoteZipUrl.path.substringAfterLast('/')
        .replace(config.version, "[revision]")

    ivy {
        name = "VendorDistributionArchiveRepo"
        url = baseUri
        if (config.allowInsecureProtocol || remoteZipUrl.scheme.equals("http", ignoreCase = true)) {
            isAllowInsecureProtocol = true
        }
        patternLayout {
            artifact(fileNamePattern)
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
            includeGroup("lv.brain.vendor.dist")
        }
    }
}

private fun ensureUnpacked(settings: Settings, config: ConsumerVendorConfig, vendorRoot: File) {
    val localDir = config.localVendorDirOverride
    if (localDir != null) {
        require(localDir.exists()) { "Configured vendor.localDir does not exist: $localDir" }
    } else if (!File(vendorRoot, ".vendor-ready").exists()) {
        val zipFile: File = config.localZipOverride ?: run {
            val dep = settings.buildscript.dependencies.create("lv.brain.vendor.dist:vendor-sdk:${config.version}@zip")
            settings.buildscript.configurations.detachedConfiguration(dep).singleFile
        }
        ZipExtractor.extractAtomically(zipFile, vendorRoot)
    }

    val ivyDir = File(vendorRoot, "ivy")
    require(ivyDir.exists()) { "Expected Ivy repository directory not found at: ${ivyDir.absolutePath}" }
}

internal class PrepareConsumerProject(
    private val config: ConsumerVendorConfig,
    private val vendorRoot: File
) : IsolatedAction<Project> {
    override fun execute(project: Project) {
        if (project.plugins.hasPlugin(VendorConsumerProjectPlugin::class.java)) return

        project.repositories.addVendorIvyRepository("VendorConsumerLocalIvy_${config.version}", vendorRoot)

        project.extensions.extraProperties.set(VENDOR_CONTEXT_KEY, ResolvedVendorContext(config, vendorRoot))

        project.plugins.apply(VendorConsumerProjectPlugin::class.java)
    }
}

/**
 * The `vendor` extension of each consumer project: builds dependency notations for the vendor distribution.
 *
 * ```kotlin
 * dependencies {
 *     implementation(vendor.bundle("client"))
 *     implementation(vendor.module(name = "example-json"))
 * }
 * ```
 */
open class VendorConsumerExtension {
    /** The effective consumer configuration from `vendor.properties`, `vendor.local.properties`, `-P` and the environment. */
    lateinit var vendorConfig: ConsumerVendorConfig
    /** The directory of the unpacked distribution (or `vendor.localDir`). */
    lateinit var vendorHomeDir: File

    private val modules: List<VendorModuleRef> by lazy { VendorModuleLookup.list(vendorHomeDir) }

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
            ext.vendorConfig = ctx.config
            ext.vendorHomeDir = ctx.vendorHomeDir
            VendorClasspathChecks.register(project, ctx)
        }
    }
}
