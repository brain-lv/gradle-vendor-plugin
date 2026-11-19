/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 *
 * Copyright (c) 2026 brain-lv (https://github.com/brain-lv)
 */

package lv.brain.gradle.distributor

import lv.brain.gradle.core.CategoryRule
import lv.brain.gradle.core.GroupRule
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.FileTree
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
    val skipped: List<String>
)

internal class JarFacts(
    val pomProperties: Triple<String, String, String>?,
    val pomXmls: List<ByteArray>,
    val manifest: Attributes?,
    val definedClasses: Set<String>
)

data class JarCoordinates(
    val group: String,
    val name: String,
    val version: String
)

object VendorJarScanner {
    const val IVY_DIR_NAME = "ivy"

    fun jarTree(vendorDir: DirectoryProperty): FileTree =
        vendorDir.asFileTree.matching {
            include("**/*.jar", "**/*.JAR")
            exclude("$IVY_DIR_NAME/**")
        }

    fun selectJars(
        vendorRoot: File,
        rules: List<CategoryRule>,
        classifier: CategoryClassifier?,
        fallbackCategory: String?
    ): JarSelection {
        if (!vendorRoot.isDirectory) return JarSelection(emptyList(), emptyList())

        val ivyDir = File(vendorRoot, IVY_DIR_NAME).canonicalFile
        val vendorDirName = vendorRoot.name

        val candidates = vendorRoot.walkTopDown()
            .onEnter { dir -> dir.canonicalFile != ivyDir }
            .filter { it.isFile && it.extension.equals("jar", ignoreCase = true) }
            .map { it to it.relativeTo(vendorRoot).invariantSeparatorsPath }
            .sortedBy { it.second }
            .toList()

        val selected = ArrayList<SelectedJar>(candidates.size)
        val skipped = ArrayList<String>()
        for ((jar, relPath) in candidates) {
            val category = classifier?.invoke(relPath, jar)
                ?: rules.firstOrNull { it.matches(relPath, vendorDirName) }?.category
                ?: fallbackCategory
            if (category == null) skipped += relPath else selected += SelectedJar(jar, relPath, category)
        }
        return JarSelection(selected, skipped)
    }

    internal fun readFacts(jar: File): JarFacts {
        var pomProperties: Triple<String, String, String>? = null
        val pomXmls = mutableListOf<ByteArray>()
        val classes = HashSet<String>()
        var manifest: Attributes? = null

        JarFile(jar).use { jf ->
            manifest = runCatching { jf.manifest?.mainAttributes }.getOrNull()
            for (entry in jf.entries()) {
                if (entry.isDirectory) continue
                val name = entry.name
                when {
                    name.startsWith("META-INF/maven/") && name.endsWith("/pom.properties") -> {
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
                    name.endsWith(".class") &&
                        !name.startsWith("META-INF/") &&
                        name != "module-info.class" &&
                        name != "package-info.class" ->
                        classes += name.removeSuffix(".class")
                }
            }
        }
        return JarFacts(pomProperties, pomXmls, manifest, classes)
    }

    internal fun resolveCoordinates(
        jar: File,
        facts: JarFacts,
        category: String,
        defaultGroup: String,
        defaultVersion: String,
        groupRules: List<GroupRule>
    ): JarCoordinates {
        facts.pomProperties?.let { (g, a, v) -> return JarCoordinates(g, a, v) }
        fromPomXml(facts.pomXmls)?.let { return it }
        fromManifest(facts.manifest, jar, defaultGroup)?.let { return it }

        for (rule in groupRules) {
            val match = rule.regex.matchEntire(jar.name)
            if (match != null) {
                val artifactName = match.groupValues.getOrElse(1) { jar.nameWithoutExtension }
                val version = match.groupValues.getOrElse(2) { defaultVersion }
                return JarCoordinates(rule.group, artifactName, version)
            }
        }

        val group = "$defaultGroup.$category".replace('-', '.')
        val (fileArtifact, fileVersion) = parseFileName(jar)
        return JarCoordinates(group, fileArtifact, fileVersion ?: defaultVersion)
    }

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

    private fun fromPomXml(pomXmls: List<ByteArray>): JarCoordinates? = runCatching {
        val bytes = pomXmls.firstOrNull() ?: return null
        val root = secureDocumentBuilderFactory().newDocumentBuilder().parse(bytes.inputStream()).documentElement

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
