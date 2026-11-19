/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 *
 * Copyright (c) 2026 brain-lv (https://github.com/brain-lv)
 */

package lv.brain.gradle.core

import org.gradle.api.initialization.Settings
import java.io.File
import java.io.Serializable

/**
 * A `vendor.rule.<n>` entry: jars whose file name matches [regex] get the group [group].
 *
 * @property regex matched against the jar file name; group 1 is the module name, group 2 the version.
 * @property group the group given to matching jars.
 */
data class GroupRule(
    val regex: Regex,
    val group: String
) : Serializable

/**
 * The include patterns of one category, from `category("<name>") { include(...) }` or `vendor.category.<name>`.
 *
 * @property category the category name.
 * @property patterns Ant-style patterns relative to the vendor directory.
 */
data class CategoryRule(
    val category: String,
    val patterns: List<String>
) : Serializable {
    @Transient
    private var compiled: Pair<String?, List<Regex>>? = null

    /**
     * Whether a jar belongs to this category.
     *
     * @param relativeUnixPath the jar path relative to the vendor directory, with `/` separators.
     * @param vendorDirName the name of the vendor directory; a pattern may start with it.
     */
    fun matches(relativeUnixPath: String, vendorDirName: String? = null): Boolean {
        val path = relativeUnixPath.replace('\\', '/').trimStart('/')
        return regexes(vendorDirName).any { it.matches(path) }
    }

    private fun regexes(vendorDirName: String?): List<Regex> {
        compiled?.let { (name, regexes) -> if (name == vendorDirName) return regexes }
        val fresh = patterns.flatMap { PathPatterns.compile(it, vendorDirName) }
        compiled = vendorDirName to fresh
        return fresh
    }
}

internal class VendorPropertySource(private val settings: Settings) {
    val rootDir: File = settings.rootDir
    private val fileProps = linkedMapOf<String, String>()

    init {
        loadRawProperties(File(rootDir, "vendor.properties"), fileProps)
        loadRawProperties(File(rootDir, "vendor.local.properties"), fileProps)
    }

    fun get(key: String, envKey: String? = null): String? {
        val providers = settings.providers
        val gradleProp = providers.gradleProperty(key).orNull
        if (!gradleProp.isNullOrBlank()) return gradleProp.trim()

        val sysProp = providers.systemProperty(key).orNull
        if (!sysProp.isNullOrBlank()) return sysProp.trim()

        if (envKey != null) {
            val envVal = providers.environmentVariable(envKey).orNull
            if (!envVal.isNullOrBlank()) return envVal.trim()
        }

        return fileProps[key]?.trim()?.takeIf { it.isNotEmpty() }
    }

    fun requireVersion(): String =
        get("vendor.version", "VENDOR_VERSION")
            ?: error("Missing required 'vendor.version' in vendor.properties, vendor.local.properties, or -Pvendor.version")

    fun defaultGroup(): String =
        get("vendor.defaultGroup") ?: "lv.brain.vendor"

    fun gitExclude(): Boolean {
        val raw = get("vendor.gitExclude", "VENDOR_GIT_EXCLUDE") ?: return true
        return raw.lowercase().toBooleanStrictOrNull()
            ?: error("Invalid vendor.gitExclude '$raw'. Use true or false.")
    }

    fun resolvePath(rawPath: String): File {
        val f = File(rawPath)
        return if (f.isAbsolute) f else File(rootDir, rawPath).canonicalFile
    }

    fun allFilePropertyKeys(): Set<String> = fileProps.keys

    fun rawFileProperty(key: String): String? = fileProps[key]?.trim()

    fun rejectDisallowedKeys(
        role: String,
        disallowedExact: Set<String>,
        disallowedPrefixes: Set<String> = emptySet()
    ) {
        val invalid = allFilePropertyKeys().filter { key ->
            key in disallowedExact || disallowedPrefixes.any { prefix -> key.startsWith(prefix) }
        }
        require(invalid.isEmpty()) {
            "Invalid properties for $role in vendor.properties:${invalid.sorted().joinToString(", ")}"
        }
    }

    private fun loadRawProperties(file: File, target: MutableMap<String, String>) {
        if (!file.exists()) return
        file.readLines(Charsets.UTF_8).forEach { rawLine ->
            val line = rawLine.trim()
            if (line.isEmpty() || line.startsWith("#") || line.startsWith("!")) return@forEach
            val sepIdx = line.indexOfFirst { it == '=' || it == ':' }
            if (sepIdx > 0) {
                val key = line.substring(0, sepIdx).trim()
                val value = line.substring(sepIdx + 1).trim()
                target[key] = value
            }
        }
    }
}

/**
 * The effective distributor configuration, available as `vendorDistributor.vendorConfig` in distributor projects.
 *
 * @property version `vendor.version`: the version of the distribution and its bundles.
 * @property defaultGroup `vendor.defaultGroup`: base group of the bundles and of jars without coordinates.
 * @property defaultCategory `vendor.defaultCategory`: category for jars that match no include.
 * @property vendorDir `vendor.dir`: the directory that holds the vendor jars.
 * @property slimJars `vendor.slimJars`: whether `-slim.jar` files are generated.
 * @property groupRules the `vendor.rule.<n>` entries.
 * @property categoryRules the category include patterns, from the DSL first and then from `vendor.category.<name>`.
 * @property defaultCategoryExplicit whether the default category was set explicitly.
 * @property compilePackages `vendor.compilePackages`: package prefixes of the compile jars; empty means all jars.
 * @property dependencyExcludeClasses `vendor.dependencyExcludeClasses`.
 * @property dependencyExcludeJars `vendor.dependencyExcludeJars`.
 * @property gitExclude `vendor.gitExclude`: whether local plugin files are kept out of Git.
 * @property patcher `vendor.patcher`: whether the numbered patches are applied before packaging. Defaults to `false`.
 * @property patchesDir `vendor.patchesDir`: the folder with the numbered patch files, `internal-patches` by default.
 * @property patchSourceDir the unpatched vendor folder when [patcher] is on; [vendorDir] is then the patched copy.
 */
data class DistributorVendorConfig(
    val version: String,
    val defaultGroup: String,
    val defaultCategory: String,
    val vendorDir: File,
    val slimJars: Boolean,
    val groupRules: List<GroupRule>,
    val categoryRules: List<CategoryRule>,
    val defaultCategoryExplicit: Boolean = false,
    val compilePackages: List<String> = emptyList(),
    val dependencyExcludeClasses: List<String> = emptyList(),
    val dependencyExcludeJars: List<String> = emptyList(),
    val gitExclude: Boolean = true,
    val patcher: Boolean = false,
    val patchesDir: File? = null,
    val patchSourceDir: File? = null
) {
    companion object {
        private val CONSUMER_ONLY_KEYS = setOf(
            "vendor.url",
            "vendor.localZip",
            "vendor.localDir",
            "vendor.cacheMode",
            "vendor.cache",
            "vendor.authToken",
            "vendor.allowInsecureProtocol",
            "vendor.clashCheck",
            "vendor.clashIgnore"
        )

        fun load(settings: Settings): DistributorVendorConfig {
            val src = VendorPropertySource(settings)
            src.rejectDisallowedKeys(role = "distributor", disallowedExact = CONSUMER_ONLY_KEYS)

            val vendorDirPath = src.get("vendor.dir", "VENDOR_DIR") ?: "vendor"
            val explicitDefaultCategory = src.get("vendor.defaultCategory")
            val defaultCategory = explicitDefaultCategory ?: "core"
            val slimJars = src.get("vendor.slimJars")?.toBooleanStrictOrNull() ?: false

            val groupRules = src.allFilePropertyKeys()
                .filter { it.startsWith("vendor.rule.") }
                .sorted()
                .mapNotNull { key ->
                    val raw = src.rawFileProperty(key)?.takeIf { it.isNotEmpty() } ?: return@mapNotNull null
                    val parts = raw.split("->", limit = 2).map { it.trim() }
                    require(parts.size == 2) { "Invalid rule '$key=$raw'. Expected format: <regex> -> <group>" }
                    GroupRule(Regex(parts[0]), parts[1])
                }

            val categoryRules = src.allFilePropertyKeys()
                .filter { it.startsWith("vendor.category.") }
                .sorted()
                .mapNotNull { key ->
                    val categoryName = key.removePrefix("vendor.category.").trim()
                    val rawPatterns = src.rawFileProperty(key)
                        ?.split(",")
                        ?.map { it.trim() }
                        ?.filter { it.isNotEmpty() }
                    if (categoryName.isNotEmpty() && !rawPatterns.isNullOrEmpty()) {
                        CategoryRule(categoryName, rawPatterns)
                    } else null
                }

            return DistributorVendorConfig(
                version = src.requireVersion(),
                defaultGroup = src.defaultGroup(),
                defaultCategory = defaultCategory,
                vendorDir = src.resolvePath(vendorDirPath),
                slimJars = slimJars,
                groupRules = groupRules,
                categoryRules = categoryRules,
                defaultCategoryExplicit = explicitDefaultCategory != null,
                compilePackages = CompilePackages.parse(src.get("vendor.compilePackages")),
                dependencyExcludeClasses = DependencyExcludes.parseClasses(src.get("vendor.dependencyExcludeClasses")),
                dependencyExcludeJars = DependencyExcludes.parseJars(src.get("vendor.dependencyExcludeJars")),
                gitExclude = src.gitExclude(),
                patcher = src.get("vendor.patcher")?.let {
                    it.lowercase().toBooleanStrictOrNull() ?: error("Invalid vendor.patcher '$it'. Use true or false.")
                } ?: false,
                patchesDir = src.resolvePath(src.get("vendor.patchesDir") ?: "internal-patches")
            )
        }
    }
}

/**
 * The effective consumer configuration, available as `vendor.vendorConfig` in consumer projects.
 *
 * @property version `vendor.version`: the distribution version to use.
 * @property defaultGroup `vendor.defaultGroup`: base group of the bundles.
 * @property distributionUrl `vendor.url`: download URL, `{version}` is replaced with [version].
 * @property allowInsecureProtocol `vendor.allowInsecureProtocol`: allows a plain `http://` URL.
 * @property localZipOverride `vendor.localZip`: a local distribution ZIP used instead of downloading.
 * @property localVendorDirOverride `vendor.localDir`: a local vendor folder used in place.
 * @property cacheMode `vendor.cacheMode`: `gradle` (`~/.gradle/caches/vendor-sdk`) or `project` (`.vendor/`).
 * @property authToken `vendor.authToken`: token for HTTP Basic authentication of the download.
 * @property clashCheck `vendor.clashCheck`: `auto`, `true` or `false`.
 * @property clashIgnore `vendor.clashIgnore`: `group:artifact` patterns the clash check skips.
 * @property gitExclude `vendor.gitExclude`: whether `vendor.local.properties` and `.vendor/` are kept out of Git.
 */
data class ConsumerVendorConfig(
    val version: String,
    val defaultGroup: String,
    val distributionUrl: String?,
    val allowInsecureProtocol: Boolean,
    val localZipOverride: File?,
    val localVendorDirOverride: File?,
    val cacheMode: String,
    val authToken: String?,
    val clashCheck: String = "auto",
    val clashIgnore: List<String> = emptyList(),
    val gitExclude: Boolean = true
) {
    companion object {
        private val DISTRIBUTOR_ONLY_KEYS = setOf(
            "vendor.dir",
            "vendor.slimJars",
            "vendor.defaultCategory",
            "vendor.compilePackages",
            "vendor.dependencyExcludeClasses",
            "vendor.dependencyExcludeJars",
            "vendor.patcher",
            "vendor.patchesDir"
        )
        private val DISTRIBUTOR_ONLY_PREFIXES = setOf(
            "vendor.rule.",
            "vendor.category."
        )

        fun load(settings: Settings): ConsumerVendorConfig {
            val src = VendorPropertySource(settings)
            src.rejectDisallowedKeys(
                role = "consumer",
                disallowedExact = DISTRIBUTOR_ONLY_KEYS,
                disallowedPrefixes = DISTRIBUTOR_ONLY_PREFIXES
            )

            val clashCheck = (src.get("vendor.clashCheck") ?: "auto").lowercase()
            require(clashCheck in setOf("auto", "true", "false")) {
                "Invalid vendor.clashCheck '$clashCheck'. Use auto, true or false."
            }

            val distributionUrl = src.get("vendor.url", "VENDOR_URL")
            val allowInsecure = src.get("vendor.allowInsecureProtocol")?.toBooleanStrictOrNull()
                ?: (distributionUrl?.startsWith("http://", ignoreCase = true) == true)

            return ConsumerVendorConfig(
                version = src.requireVersion(),
                defaultGroup = src.defaultGroup(),
                distributionUrl = distributionUrl,
                allowInsecureProtocol = allowInsecure,
                localZipOverride = src.get("vendor.localZip", "VENDOR_LOCAL_ZIP")?.let { src.resolvePath(it) },
                localVendorDirOverride = src.get("vendor.localDir", "VENDOR_LOCAL_DIR")?.let { src.resolvePath(it) },
                cacheMode = src.get("vendor.cacheMode", "VENDOR_CACHE_MODE")
                    ?: src.get("vendor.cache", "VENDOR_CACHE")
                    ?: "gradle",
                authToken = src.get("vendor.authToken", "VENDOR_AUTH_TOKEN"),
                clashCheck = clashCheck,
                clashIgnore = src.get("vendor.clashIgnore")
                    ?.split(',')?.map { it.trim() }?.filter { it.isNotEmpty() }.orEmpty(),
                gitExclude = src.gitExclude()
            )
        }
    }
}

object CompilePackages {
    fun normalize(raw: String): String? =
        raw.trim().replace('/', '.').removeSuffix("*").trim('.').takeIf { it.isNotEmpty() }

    fun parse(raw: String?): List<String> =
        raw?.split(',')?.mapNotNull { normalize(it) }?.distinct().orEmpty()

    fun internalPrefixes(prefixes: Collection<String>): List<String> =
        prefixes.mapNotNull { normalize(it) }.distinct().map { it.replace('.', '/') + "/" }
}
