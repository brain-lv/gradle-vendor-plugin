/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 *
 * Copyright (c) 2026 Edgars Grinvalds <edgars.grinvalds@brain.lv>
 */

package lv.brain.gradle.distributor

import lv.brain.gradle.core.CategoryRule
import lv.brain.gradle.core.GroupRule
import lv.brain.gradle.core.ScanExcludes
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.FileTree
import org.gradle.api.provider.Provider
import org.w3c.dom.Element
import java.io.File
import java.security.MessageDigest
import java.util.Properties
import java.util.jar.Attributes
import java.util.jar.JarFile
import javax.xml.XMLConstants
import javax.xml.parsers.DocumentBuilderFactory

/**
 * Assigns a jar to a category by code, see [VendorDistributorSettingsExtension.classifier].
 *
 * Receives the jar path relative to the vendor directory (with `/` separators) and the jar file; returns the category
 * name, or `null` to leave the jar to the include patterns.
 */
typealias CategoryClassifier = (relativeUnixPath: String, jarFile: File) -> String?

data class SelectedJar(
    val file: File,
    val relativePath: String,
    val category: String
)

data class JarSelection(
    val jars: List<SelectedJar>,
    val skipped: List<String>,
    val defaulted: List<String> = emptyList()
)

internal class JarFacts(
    val pomProperties: Triple<String, String, String>?,
    val pomXmls: List<ByteArray>,
    val manifest: Attributes?,
    val definedClasses: Set<String>,
    val pluginIds: List<String> = emptyList(),
    val contentKey: String = "",
    val explicitModuleName: String? = null
) {
    val automaticModuleName: String? get() = manifest?.getValue("Automatic-Module-Name")?.trim()?.takeIf { it.isNotEmpty() }
    val singlePom: Element? by lazy { pomXmls.singleOrNull()?.let { VendorJarScanner.parsePom(it) } }
}

data class JarCoordinates(
    val group: String,
    val name: String,
    val version: String
)

object VendorJarScanner {
    const val IVY_DIR_NAME = "ivy"
    private const val GRADLE_PLUGINS_DIR = "META-INF/gradle-plugins/"

    fun jarTree(vendorDir: DirectoryProperty, scanExcludes: Provider<List<String>>): FileTree =
        vendorDir.asFileTree.matching {
            include("**/*.jar", "**/*.JAR")
            exclude(ScanExcludes.gradlePatterns(scanExcludes.getOrElse(emptyList()), vendorDir.get().asFile.name))
        }

    fun selectJars(
        vendorRoot: File,
        rules: List<CategoryRule>,
        classifier: CategoryClassifier?,
        fallbackCategory: String?,
        scanExcludes: List<String> = emptyList()
    ): JarSelection {
        if (!vendorRoot.isDirectory) return JarSelection(emptyList(), emptyList())

        val vendorDirName = vendorRoot.name
        val excludes = ScanExcludes(scanExcludes, vendorDirName)

        val candidates = vendorRoot.walkTopDown()
            .onEnter { dir -> dir == vendorRoot || !excludes.excludesDir(dir.relativeTo(vendorRoot).invariantSeparatorsPath) }
            .filter { it.isFile && it.extension.equals("jar", ignoreCase = true) }
            .map { it to it.relativeTo(vendorRoot).invariantSeparatorsPath }
            .filter { (_, relPath) -> !excludes.excludesFile(relPath) }
            .sortedBy { it.second }
            .toList()

        val selected = ArrayList<SelectedJar>(candidates.size)
        val skipped = ArrayList<String>()
        val defaulted = ArrayList<String>()
        for ((jar, relPath) in candidates) {
            val matched = classifier?.invoke(relPath, jar)
                ?: rules.firstOrNull { it.matches(relPath, vendorDirName) }?.category
            val category = matched ?: fallbackCategory
            if (matched == null && category != null) defaulted += relPath
            if (category == null) skipped += relPath else selected += SelectedJar(jar, relPath, category)
        }
        return JarSelection(selected, skipped, defaulted)
    }

    internal fun readFacts(jar: File): JarFacts {
        var pomProperties: Triple<String, String, String>? = null
        var pomPropertiesCount = 0
        val pomXmls = mutableListOf<ByteArray>()
        val classes = HashSet<String>()
        val pluginIds = sortedSetOf<String>()
        var manifest: Attributes? = null
        val fingerprint = java.util.TreeMap<String, String>()
        var moduleName: String? = null

        JarFile(jar).use { jf ->
            manifest = runCatching { jf.manifest?.mainAttributes }.getOrNull()
            for (entry in jf.entries()) {
                if (entry.isDirectory) continue
                val name = entry.name
                fingerprint[name] = "${entry.crc}:${entry.size}"
                if (moduleName == null && (name == "module-info.class" || Regex("META-INF/versions/\\d+/module-info\\.class").matches(name))) {
                    moduleName = runCatching { readModuleName(jf.getInputStream(entry).use { it.readBytes() }) }.getOrNull()
                }
                when {
                    name.startsWith("META-INF/maven/") && name.endsWith("/pom.properties") -> {
                        pomPropertiesCount++
                        if (pomProperties == null) {
                            val props = Properties().apply { jf.getInputStream(entry).use { load(it) } }
                            val g = props.getProperty("groupId")?.trim()?.takeIf { it.isNotEmpty() }
                            val a = props.getProperty("artifactId")?.trim()?.takeIf { it.isNotEmpty() }
                            val v = props.getProperty("version")?.trim()?.takeIf { it.isNotEmpty() }
                            if (g != null && a != null && v != null) pomProperties = Triple(g, a, v)
                        }
                    }
                    name.startsWith("META-INF/maven/") && name.endsWith("/pom.xml") ->
                        pomXmls += jf.getInputStream(entry).use { it.readBytes() }
                    name.startsWith(GRADLE_PLUGINS_DIR) && name.endsWith(".properties") && '/' !in name.removePrefix(GRADLE_PLUGINS_DIR) ->
                        name.removePrefix(GRADLE_PLUGINS_DIR).removeSuffix(".properties").takeIf { it.isNotEmpty() }?.let { pluginIds += it }
                    name.endsWith(".class") &&
                        !name.startsWith("META-INF/") &&
                        name != "module-info.class" &&
                        name != "package-info.class" ->
                        classes += name.removeSuffix(".class")
                }
            }
        }
        val digest = MessageDigest.getInstance("SHA-256")
        for ((name, value) in fingerprint) digest.update("$name\u0000$value\n".toByteArray(Charsets.UTF_8))
        val contentKey = digest.digest().joinToString("") { "%02x".format(it) }
        return JarFacts(pomProperties.takeIf { pomPropertiesCount == 1 }, pomXmls, manifest, classes, pluginIds.toList(), contentKey, moduleName)
    }

    private fun readModuleName(bytes: ByteArray): String? {
        var found: String? = null
        org.objectweb.asm.ClassReader(bytes).accept(
            object : org.objectweb.asm.ClassVisitor(org.objectweb.asm.Opcodes.ASM9) {
                override fun visitModule(name: String?, access: Int, version: String?): org.objectweb.asm.ModuleVisitor? {
                    found = name
                    return null
                }
            },
            org.objectweb.asm.ClassReader.SKIP_CODE
        )
        return found
    }

    internal fun resolveCoordinates(
        jar: File,
        facts: JarFacts,
        category: String,
        defaultGroup: String,
        defaultVersion: String,
        groupRules: List<GroupRule>
    ): JarCoordinates = identify(jar, facts, category, defaultGroup, defaultVersion, groupRules).first

    internal fun identify(
        jar: File,
        facts: JarFacts,
        category: String,
        defaultGroup: String,
        defaultVersion: String,
        groupRules: List<GroupRule>
    ): Pair<JarCoordinates, String> {
        facts.pomProperties?.let { (g, a, v) -> return JarCoordinates(g, a, v) to IDENTITY_POM_PROPERTIES }
        fromPomXml(facts.singlePom)?.let { return it to IDENTITY_POM_XML }

        matchingRule(jar, groupRules)?.let { (rule, match) ->
            val artifactName = match.groupValues.getOrNull(1)?.takeIf { it.isNotEmpty() } ?: jar.nameWithoutExtension
            val version = match.groupValues.getOrNull(2)?.takeIf { it.isNotEmpty() }
                ?: cleanVersion(facts.manifest?.getValue("Implementation-Version"))
                ?: defaultVersion
            return JarCoordinates(rule.group, artifactName, version) to IDENTITY_RULE
        }

        fromManifest(facts.manifest, jar, defaultGroup)?.let { return it to IDENTITY_MANIFEST }

        val group = "$defaultGroup.$category".replace('-', '.')
        val (fileArtifact, fileVersion) = parseFileName(jar)
        return JarCoordinates(group, fileArtifact, fileVersion ?: defaultVersion) to IDENTITY_FILE_NAME
    }

    internal fun matchingRule(jar: File, groupRules: List<GroupRule>): Pair<GroupRule, MatchResult>? =
        groupRules.firstNotNullOfOrNull { rule -> rule.regex.matchEntire(jar.name)?.let { rule to it } }

    internal const val IDENTITY_POM_PROPERTIES = "pom.properties"
    internal const val IDENTITY_POM_XML = "pom.xml"
    internal const val IDENTITY_MANIFEST = "manifest"
    internal const val IDENTITY_RULE = "rule"
    internal const val IDENTITY_FILE_NAME = "file-name"

    internal fun parseFileName(jar: File): Pair<String, String?> {
        val match = DEFAULT_FILENAME_REGEX.matchEntire(jar.name)
        return if (match != null) match.groupValues[1] to match.groupValues[2] else jar.nameWithoutExtension to null
    }

    internal fun sameContent(a: File, b: File): Boolean {
        if (a.length() != b.length()) return false
        fun sha256(f: File): ByteArray {
            val digest = MessageDigest.getInstance("SHA-256")
            f.inputStream().buffered(64 * 1024).use { input ->
                val buffer = ByteArray(64 * 1024)
                while (true) {
                    val read = input.read(buffer)
                    if (read < 0) break
                    digest.update(buffer, 0, read)
                }
            }
            return digest.digest()
        }
        return sha256(a).contentEquals(sha256(b))
    }

    internal fun secureDocumentBuilderFactory(): DocumentBuilderFactory =
        DocumentBuilderFactory.newInstance().apply {
            setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true)
            setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
            setFeature("http://xml.org/sax/features/external-general-entities", false)
            setFeature("http://xml.org/sax/features/external-parameter-entities", false)
            isExpandEntityReferences = false
        }

    private val DEFAULT_FILENAME_REGEX =
        Regex("""^([a-zA-Z0-9._-]+?)-(\d+(?:\.\d+)*(?:-[a-zA-Z0-9._-]+)?)\.jar$""")

    internal fun parsePom(bytes: ByteArray): Element? = runCatching {
        secureDocumentBuilderFactory().newDocumentBuilder().parse(bytes.inputStream()).documentElement
            ?.takeIf { it.tagName == "project" || it.localName == "project" }
    }.getOrNull()

    private fun fromPomXml(pom: Element?): JarCoordinates? = runCatching {
        val root = pom ?: return null

        fun direct(parent: Element, tag: String): String? {
            val children = parent.childNodes
            for (i in 0 until children.length) {
                val child = children.item(i) as? Element ?: continue
                if (child.tagName == tag) return child.textContent?.trim()?.takeIf { it.isNotEmpty() }
            }
            return null
        }

        val parent = (0 until root.childNodes.length)
            .mapNotNull { root.childNodes.item(it) as? Element }
            .firstOrNull { it.tagName == "parent" }

        val group = direct(root, "groupId") ?: parent?.let { direct(it, "groupId") } ?: return null
        val artifact = direct(root, "artifactId") ?: return null
        val version = direct(root, "version") ?: parent?.let { direct(it, "version") } ?: return null
        if (group.contains("\${") || version.contains("\${")) return null
        JarCoordinates(group, artifact, version)
    }.getOrNull()

    private val VERSION_TOKEN = Regex("""^[0-9A-Za-z][0-9A-Za-z._+\-]*$""")

    private val GROUP_LIKE = Regex("""^[a-z][a-z0-9_]*(?:\.[a-z][a-z0-9_-]*){2,}$""")

    internal fun cleanVersion(raw: String?): String? =
        raw?.trim()?.split(Regex("\\s+"))?.firstOrNull()?.takeIf { VERSION_TOKEN.matches(it) }

    private val PLACEHOLDER_GROUPS = setOf("undefined", "unknown", "null", "none", "n/a", "na", "tbd", "todo", "default", "-")
    private val GROUP_ID = Regex("""[A-Za-z_][A-Za-z0-9_.-]*""")

    internal fun usableGroup(raw: String?): String? {
        val value = raw?.trim().orEmpty()
        if (value.isEmpty() || value.lowercase() in PLACEHOLDER_GROUPS || !GROUP_ID.matches(value)) return null
        if ('.' !in value && value != value.lowercase()) return null
        return value
    }

    private fun safeSegment(value: String): String =
        value.trim().replace(Regex("[^A-Za-z0-9._+-]+"), "-").trim('-')

    private fun fromManifest(attrs: Attributes?, jar: File, defaultGroup: String): JarCoordinates? = runCatching {
        attrs ?: return null
        val version = listOf("Bundle-Version", "Implementation-Version", "Specification-Version")
            .firstNotNullOfOrNull { cleanVersion(attrs.getValue(it)) }
            ?: return null

        val symbolicName = attrs.getValue("Bundle-SymbolicName")?.substringBefore(";")?.trim()?.takeIf { it.isNotEmpty() }
        val implTitle = attrs.getValue("Implementation-Title")?.trim()?.takeIf { it.isNotEmpty() }
        val vendorId = usableGroup(attrs.getValue("Implementation-Vendor-Id"))
        val titleIsGroup = symbolicName == null && implTitle != null && GROUP_LIKE.matches(implTitle)

        val name = when {
            symbolicName != null -> symbolicName
            titleIsGroup -> parseFileName(jar).first
            implTitle != null -> implTitle
            else -> return null
        }
        val group = vendorId ?: implTitle.takeIf { titleIsGroup } ?: defaultGroup
        JarCoordinates(group, safeSegment(name), version)
    }.getOrNull()
}
