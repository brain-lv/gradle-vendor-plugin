/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 *
 * Copyright (c) 2026 Edgars Grinvalds <edgars.grinvalds@brain.lv>
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
 * @property origin `open-source` for a public open-source library, `vendor-public` for vendor code that is published in a
 *   public repository, `vendor-private` for vendor code that is not, or `null` when not said.
 */
data class GroupRule(
    val regex: Regex,
    val group: String,
    val origin: String? = null
) : Serializable {
    companion object {
        /** The allowed values of [origin]. */
        val ORIGINS = setOf("open-source", "vendor-public", "vendor-private")

        /** Short forms accepted for [origin]. */
        val ORIGIN_ALIASES = mapOf("oss" to "open-source", "vendor" to "vendor-private")

        /** The origins whose jars can be found in a public Maven repository. */
        val PUBLISHED_ORIGINS = setOf("open-source", "vendor-public")

        /** Reads `<regex> -> <group>` or `<regex> -> <group> open-source|vendor-public|vendor-private`. */
        fun parse(key: String, raw: String): GroupRule {
            val parts = raw.split("->", limit = 2).map { it.trim() }
            require(parts.size == 2 && parts[1].isNotEmpty()) {
                "Invalid rule '$key=$raw'. Expected format: <regex> -> <group> [open-source|vendor-public|vendor-private]"
            }
            val target = parts[1].split(Regex("\\s+"))
            val origin = target.getOrNull(1)?.lowercase()?.let { ORIGIN_ALIASES[it] ?: it }
            require(target.size <= 2 && (origin == null || origin in ORIGINS)) {
                "Invalid rule '$key=$raw': after the group only open-source (a public open-source library), vendor-public " +
                    "(vendor code published in a public repository) or vendor-private (vendor code that is not published) may follow."
            }
            return GroupRule(Regex(parts[0]), target[0], origin)
        }
    }
}

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
 * @property scanExcludes `vendor.scanExcludes`: folders and files of the vendor directory that are never scanned or packaged.
 * @property zipInclude `vendor.zip.include`: files of the vendor directory packaged into the distribution. Defaults to all.
 * @property zipExclude `vendor.zip.exclude`: files left out of the distribution.
 * @property slimZip `vendor.slimZip`: whether `vendor-sdk-<version>-slim.zip` is built as well. Defaults to `false`.
 * @property slimZipInclude `vendor.slimZip.include`: further files packaged into the slim distribution. Defaults to none.
 * @property slimZipExclude `vendor.slimZip.exclude`: files left out of the slim distribution.
 * @property dependencyAdd `vendor.dependencyAdd`: dependencies (`from->to` module names) added to the found ones.
 * @property dependencyRemove `vendor.dependencyRemove`: found dependencies (`from->to`) that are dropped.
 * @property pomCheck `vendor.pomCheck`: `off`, `warn` or `add`, whether jars with a `pom.xml` are also checked against
 *   their code and missing dependencies only reported or also added. Defaults to `off`.
 * @property missingClasses `vendor.missingClasses`: `warn`, `fail` or `off` for classes that are in no vendor jar and not in the JDK.
 * @property missingClassesScope `vendor.missingClasses.scope`: `vendor` (classes used by the vendor's own jars) or `all`.
 * @property publishUrl `vendor.publish.url`: the Maven repository that `publishVendorMavenRepository` uploads to.
 * @property publishUsername `vendor.publish.username`: the user name for [publishUrl].
 * @property diffBaseline `vendor.diff.baseline`: an earlier distribution ZIP that `diffVendorDistribution` compares with.
 * @property signingKeyFile `vendor.signing.key`: the private key file that signs the distribution ZIPs.
 * @property jpmsCheck `vendor.jpmsCheck`: `warn`, `fail` or `off` for vendor jars that would break on the Java module path.
 * @property mavenCheckUrl `vendor.mavenCheck.url`: the Maven repository `verifyVendorAgainstMaven` compares with.
 * @property mavenCheckFailOn `vendor.mavenCheck.failOn`: the findings of `verifyVendorAgainstMaven` that fail the build.
 * @property licensesAllow `vendor.licenses.allow`: licence patterns a jar may have (empty: any that is not denied).
 * @property licensesDeny `vendor.licenses.deny`: licence patterns no jar may have.
 * @property licensesUnknown `vendor.licenses.unknown`: `warn`, `fail` or `off` for jars without a known licence.
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
    val patchSourceDir: File? = null,
    val scanExcludes: List<String> = emptyList(),
    val zipInclude: List<String> = listOf("**"),
    val zipExclude: List<String> = emptyList(),
    val slimZip: Boolean = false,
    val slimZipInclude: List<String> = emptyList(),
    val slimZipExclude: List<String> = emptyList(),
    val dependencyAdd: List<String> = emptyList(),
    val dependencyRemove: List<String> = emptyList(),
    val pomCheck: String = "off",
    val missingClasses: String = "warn",
    val missingClassesScope: String = "vendor",
    val publishUrl: String? = null,
    val publishUsername: String? = null,
    val diffBaseline: File? = null,
    val signingKeyFile: File? = null,
    val jpmsCheck: String = "warn",
    val mavenCheckUrl: String = "https://repo.maven.apache.org/maven2",
    val mavenCheckFailOn: List<String> = emptyList(),
    val licensesAllow: List<String> = emptyList(),
    val licensesDeny: List<String> = emptyList(),
    val licensesUnknown: String = "warn"
) {
    companion object {
        internal fun pomCheckMode(raw: String, key: String): String = when (raw.trim().lowercase()) {
            "false", "off" -> "off"
            "true", "warn" -> "warn"
            "add" -> "add"
            else -> error("Invalid $key '$raw'. Use off, warn or add.")
        }

        internal fun missingClassesMode(raw: String, key: String): String = raw.trim().lowercase().also {
            require(it in setOf("warn", "fail", "off")) { "Invalid $key '$raw'. Use warn, fail or off." }
        }

        private val CONSUMER_ONLY_KEYS = setOf(
            "vendor.distributions",
            "vendor.sha256",
            "vendor.signing.publicKey",
            "vendor.duplicateClasses",
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
            VendorSample.check(settings, VendorSample.Role.DISTRIBUTOR)
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
                    GroupRule.parse(key, raw)
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
                patchesDir = src.resolvePath(src.get("vendor.patchesDir") ?: "internal-patches"),
                scanExcludes = ScanExcludes.parse(src.get("vendor.scanExcludes")),
                zipInclude = src.get("vendor.zip.include")?.let { DependencyExcludes.parseList(it) } ?: listOf("**"),
                zipExclude = DependencyExcludes.parseList(src.get("vendor.zip.exclude")),
                slimZip = src.get("vendor.slimZip")?.let {
                    it.lowercase().toBooleanStrictOrNull() ?: error("Invalid vendor.slimZip '$it'. Use true or false.")
                } ?: false,
                slimZipInclude = DependencyExcludes.parseList(src.get("vendor.slimZip.include")),
                slimZipExclude = DependencyExcludes.parseList(src.get("vendor.slimZip.exclude")),
                dependencyAdd = DependencyOverrides.parse(src.get("vendor.dependencyAdd"), "vendor.dependencyAdd"),
                dependencyRemove = DependencyOverrides.parse(src.get("vendor.dependencyRemove"), "vendor.dependencyRemove"),
                pomCheck = src.get("vendor.pomCheck")?.let { pomCheckMode(it, "vendor.pomCheck") } ?: "off",
                missingClasses = src.get("vendor.missingClasses")?.let { missingClassesMode(it, "vendor.missingClasses") } ?: "warn",
                missingClassesScope = (src.get("vendor.missingClasses.scope") ?: "vendor").trim().lowercase().also {
                    require(it in setOf("vendor", "all")) { "Invalid vendor.missingClasses.scope '$it'. Use vendor or all." }
                },
                publishUrl = src.get("vendor.publish.url", "VENDOR_PUBLISH_URL"),
                publishUsername = src.get("vendor.publish.username", "VENDOR_PUBLISH_USERNAME"),
                diffBaseline = src.get("vendor.diff.baseline")?.let { src.resolvePath(it) },
                signingKeyFile = src.get("vendor.signing.key")?.let { src.resolvePath(it) },
                jpmsCheck = src.get("vendor.jpmsCheck")?.let { missingClassesMode(it, "vendor.jpmsCheck") } ?: "warn",
                mavenCheckUrl = src.get("vendor.mavenCheck.url", "VENDOR_MAVEN_CHECK_URL") ?: "https://repo.maven.apache.org/maven2",
                mavenCheckFailOn = DependencyExcludes.parseList(src.get("vendor.mavenCheck.failOn")).map { it.lowercase() }.also { values ->
                    val unknown = values - setOf("differs", "notfound", "dependencies", "licenses")
                    require(unknown.isEmpty()) {
                        "Invalid vendor.mavenCheck.failOn ${unknown.joinToString()}. Use differs, notfound, dependencies and/or licenses."
                    }
                },
                licensesAllow = DependencyExcludes.parseList(src.get("vendor.licenses.allow")),
                licensesDeny = DependencyExcludes.parseList(src.get("vendor.licenses.deny")),
                licensesUnknown = src.get("vendor.licenses.unknown")?.let { missingClassesMode(it, "vendor.licenses.unknown") } ?: "warn"
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
 * @property sha256 `vendor.sha256`: the SHA-256 the distribution ZIP must have, checked before it is unpacked.
 * @property duplicateClasses `vendor.duplicateClasses`: `warn`, `fail` or `off` for classes that a vendor jar shares with
 *   another jar of the runtime classpath.
 * @property signingPublicKey `vendor.signing.publicKey`: the public key whose signature the distribution ZIP must carry.
 * @property name the name from `vendor.distributions`, or `null` for the default distribution.
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
    val gitExclude: Boolean = true,
    val sha256: String? = null,
    val duplicateClasses: String = "warn",
    val signingPublicKey: String? = null,
    val name: String? = null
) {
    /** The name used in messages: `default` for the unnamed distribution. */
    val displayName: String get() = name ?: "default"
    companion object {
        private val DISTRIBUTOR_ONLY_KEYS = setOf(
            "vendor.dir",
            "vendor.slimJars",
            "vendor.defaultCategory",
            "vendor.compilePackages",
            "vendor.dependencyExcludeClasses",
            "vendor.dependencyExcludeJars",
            "vendor.patcher",
            "vendor.patchesDir",
            "vendor.scanExcludes",
            "vendor.zip.include",
            "vendor.zip.exclude",
            "vendor.slimZip",
            "vendor.slimZip.include",
            "vendor.slimZip.exclude",
            "vendor.dependencyAdd",
            "vendor.dependencyRemove",
            "vendor.pomCheck",
            "vendor.missingClasses",
            "vendor.missingClasses.scope",
            "vendor.publish.url",
            "vendor.publish.username",
            "vendor.publish.password",
            "vendor.diff.baseline",
            "vendor.signing.key",
            "vendor.jpmsCheck",
            "vendor.mavenCheck.url",
            "vendor.mavenCheck.failOn",
            "vendor.licenses.allow",
            "vendor.licenses.deny",
            "vendor.licenses.unknown"
        )
        private val DISTRIBUTOR_ONLY_PREFIXES = setOf(
            "vendor.rule.",
            "vendor.category."
        )

        private val RESERVED_NAMES = setOf(
            "default", "signing", "publish", "diff", "zip", "slimzip", "rule", "category", "local", "cache", "distributions"
        )
        private val NAME = Regex("[a-z][a-z0-9_-]*")

        /** The default distribution, or the first named one when only named distributions are configured. */
        fun load(settings: Settings): ConsumerVendorConfig = loadAll(settings).first()

        /**
         * Every distribution the build uses: the default one (`vendor.version`, `vendor.url`, ...) and each name of
         * `vendor.distributions` (`vendor.<name>.version`, `vendor.<name>.url`, ...).
         */
        fun loadAll(settings: Settings): List<ConsumerVendorConfig> {
            VendorSample.check(settings, VendorSample.Role.CONSUMER)
            val src = VendorPropertySource(settings)
            src.rejectDisallowedKeys(
                role = "consumer",
                disallowedExact = DISTRIBUTOR_ONLY_KEYS,
                disallowedPrefixes = DISTRIBUTOR_ONLY_PREFIXES
            )
            val names = DependencyExcludes.parseList(src.get("vendor.distributions")).map { it.lowercase() }
            names.forEach { name ->
                require(NAME.matches(name) && name !in RESERVED_NAMES) {
                    "Invalid name '$name' in vendor.distributions. Use lower-case letters, digits, '-' and '_' " +
                        "(not ${RESERVED_NAMES.sorted().joinToString()})."
                }
            }
            require(names.size == names.distinct().size) { "vendor.distributions lists a name twice: ${names.joinToString()}" }

            val clashCheck = (src.get("vendor.clashCheck") ?: "auto").lowercase()
            require(clashCheck in setOf("auto", "true", "false")) {
                "Invalid vendor.clashCheck '$clashCheck'. Use auto, true or false."
            }
            val shared = ConsumerVendorConfig(
                version = "",
                defaultGroup = src.defaultGroup(),
                distributionUrl = null,
                allowInsecureProtocol = false,
                localZipOverride = null,
                localVendorDirOverride = null,
                cacheMode = src.get("vendor.cacheMode", "VENDOR_CACHE_MODE")
                    ?: src.get("vendor.cache", "VENDOR_CACHE")
                    ?: "gradle",
                authToken = null,
                clashCheck = clashCheck,
                clashIgnore = src.get("vendor.clashIgnore")
                    ?.split(',')?.map { it.trim() }?.filter { it.isNotEmpty() }.orEmpty(),
                gitExclude = src.gitExclude(),
                duplicateClasses = (src.get("vendor.duplicateClasses") ?: "warn").trim().lowercase().also {
                    require(it in setOf("warn", "fail", "off")) { "Invalid vendor.duplicateClasses '$it'. Use warn, fail or off." }
                }
            )

            val result = ArrayList<ConsumerVendorConfig>()
            if (names.isEmpty() || src.get("vendor.version", "VENDOR_VERSION") != null) result += distribution(src, null, shared)
            names.forEach { result += distribution(src, it, shared) }
            return result
        }

        private fun distribution(src: VendorPropertySource, name: String?, shared: ConsumerVendorConfig): ConsumerVendorConfig {
            fun key(k: String) = if (name == null) "vendor.$k" else "vendor.$name.$k"
            fun env(k: String) = if (name == null) "VENDOR_$k" else "VENDOR_${name.uppercase().replace('-', '_')}_$k"
            fun get(k: String, envKey: String? = null) = src.get(key(k), envKey?.let(::env))

            val version = get("version", "VERSION")
                ?: error("Missing required '${key("version")}' in vendor.properties, vendor.local.properties, or -P${key("version")}")
            val distributionUrl = get("url", "URL")
            val allowInsecure = get("allowInsecureProtocol")?.toBooleanStrictOrNull()
                ?: (distributionUrl?.startsWith("http://", ignoreCase = true) == true)
            val publicKey = (get("signing.publicKey", "SIGNING_PUBLIC_KEY") ?: name?.let { src.get("vendor.signing.publicKey", "VENDOR_SIGNING_PUBLIC_KEY") })
                ?.let { VendorSignature.readKeyText(it, src::resolvePath) }
                ?.also { VendorSignature.publicKey(it) }
            return shared.copy(
                name = name,
                version = version,
                defaultGroup = get("defaultGroup") ?: shared.defaultGroup,
                distributionUrl = distributionUrl,
                allowInsecureProtocol = allowInsecure,
                localZipOverride = get("localZip", "LOCAL_ZIP")?.let { src.resolvePath(it) },
                localVendorDirOverride = get("localDir", "LOCAL_DIR")?.let { src.resolvePath(it) },
                authToken = get("authToken", "AUTH_TOKEN"),
                sha256 = get("sha256", "SHA256")?.trim()?.lowercase()?.also {
                    require(Regex("[0-9a-f]{64}").matches(it)) { "Invalid ${key("sha256")} '$it'. Use the 64 hex digits of the SHA-256." }
                },
                signingPublicKey = publicKey
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
