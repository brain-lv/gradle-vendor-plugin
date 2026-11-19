/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 *
 * Copyright (c) 2026 brain-lv (https://github.com/brain-lv)
 */

package lv.brain.gradle.distributor

import groovy.json.JsonSlurper
import lv.brain.gradle.core.VendorRepositoryLayout
import lv.brain.gradle.distributor.VendorIvyDependencyMapper.ResolvedDep
import lv.brain.gradle.distributor.VendorIvyDependencyMapper.ResolvedModule
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

// Every generated metadata file must be well-formed: an XML file that does not start exactly with the XML declaration
// is rejected by browsers (Edge shows a parsing error) and by strict XML parsers.
class IvyRepositoryGeneratorTest {

    @TempDir
    lateinit var tempDir: File

    private fun module(
        name: String,
        vararg deps: String,
        categories: List<String> = listOf("client"),
        compile: Boolean = true
    ) = ResolvedModule(
        moduleId = name,
        groupId = "lv.brain.vendor.client",
        artifactId = name,
        version = "1.0.0",
        jarRelativePath = "client/lib/$name-1.0.0.jar",
        resolutionSource = "test",
        dependsOn = deps.map { ResolvedDep(it, "lv.brain.vendor.client", it, "1.0.0") },
        categories = categories,
        compile = compile
    )

    @Test
    fun `all generated xml and json files are well formed and start without leading whitespace`() {
        val vendorRoot = File(tempDir, "vendor")
        val ivyDir = File(vendorRoot, "ivy")
        // several modules in one category: the bundle descriptor lists all of them
        val modules = listOf(module("alpha", "beta"), module("beta"), module("gamma"))

        IvyRepositoryGenerator.generate(modules, vendorRoot, ivyDir, "2026.1.0", "lv.brain.vendor.bundle", slimJars = false)

        val xmlFiles = ivyDir.walkTopDown().filter { it.isFile && it.extension == "xml" }.toList()
        val jsonFiles = ivyDir.walkTopDown().filter { it.isFile && it.extension == "module" }.toList()
        assertEquals(4, xmlFiles.size, "three modules and one bundle: $xmlFiles")
        assertEquals(4, jsonFiles.size, "three modules and one bundle: $jsonFiles")

        for (file in xmlFiles) {
            assertTrue(file.readText().startsWith("<?xml version=\"1.0\" encoding=\"UTF-8\"?>"), "${file.name} must start with the xml declaration")
            DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(file) // throws when not well-formed
        }
        for (file in jsonFiles) {
            JsonSlurper().parse(file)
        }

        val bundle = xmlFiles.single { it.path.contains("bundle") }
        val dependencies = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(bundle).getElementsByTagName("dependency")
        assertEquals(listOf("alpha", "beta", "gamma"), (0 until dependencies.length).map { dependencies.item(it).attributes.getNamedItem("name").nodeValue })
    }

    @Test
    fun `jar urls in the module metadata never leave the vendor folder that is the repository root`() {
        val vendorRoot = File(tempDir, "vendor")
        val ivyDir = File(vendorRoot, "ivy")

        IvyRepositoryGenerator.generate(listOf(module("alpha")), vendorRoot, ivyDir, "2026.1.0", "lv.brain.vendor.bundle", slimJars = false)

        val moduleFile = ivyDir.walkTopDown().single { it.name == "alpha-1.0.0.module" }
        val parsed = JsonSlurper().parse(moduleFile) as Map<*, *>
        val urls = (parsed["variants"] as List<*>).flatMap { variant ->
            ((variant as Map<*, *>)["files"] as List<*>).map { (it as Map<*, *>)["url"] as String }
        }
        assertEquals(listOf("../../../../client/lib/alpha-1.0.0.jar", "../../../../client/lib/alpha-1.0.0.jar"), urls)

        // The jar sits next to the ivy folder, so the repository root has to be the vendor folder: the path
        // goes up out of ivy/<group>/<module>/<version> but stays inside the root.
        for (url in urls) {
            val target = moduleFile.parentFile.toPath().resolve(url).normalize()
            assertTrue(target.startsWith(vendorRoot.toPath().normalize()), "$target is outside $vendorRoot")
            assertTrue(!target.startsWith(ivyDir.toPath().normalize()), "$target should not be inside the ivy folder")
        }
    }

    @Test
    fun `repository root uri ends with a slash even when the folder does not exist yet`() {
        val missing = File(tempDir, "not-created-yet")

        val uri = VendorRepositoryLayout.rootUri(missing).toString()

        assertTrue(uri.endsWith("/") && !uri.endsWith("//"), uri)
        assertTrue(uri.startsWith("file:"), uri)
        assertEquals("ivy/[organisation]/[module]/[revision]/ivy-[revision].xml", VendorRepositoryLayout.IVY_PATTERN)
    }

    // --- compile / runtime split ---

    private fun parseModule(ivyDir: File, name: String): Map<*, *> =
        JsonSlurper().parse(ivyDir.walkTopDown().single { it.name == "$name-1.0.0.module" }) as Map<*, *>

    private fun variant(module: Map<*, *>, name: String): Map<*, *> =
        (module["variants"] as List<*>).map { it as Map<*, *> }.single { it["name"] == name }

    private fun depNames(variant: Map<*, *>): List<String> =
        (variant["dependencies"] as List<*>).map { (it as Map<*, *>)["module"] as String }

    private fun fileUrls(variant: Map<*, *>): List<String> =
        ((variant["files"] as? List<*>).orEmpty()).map { (it as Map<*, *>)["url"] as String }

    @Test
    fun `bundles and compile modules only pull in compile modules while a runtime only module still offers its jar when named`() {
        val vendorRoot = File(tempDir, "vendor")
        val ivyDir = File(vendorRoot, "ivy")
        val modules = listOf(
            module("api", "lib", "rt"),
            module("lib"),
            module("rt", "lib", compile = false)
        )

        IvyRepositoryGenerator.generate(modules, vendorRoot, ivyDir, "2026.1.0", "lv.brain.vendor.bundle", slimJars = false)

        // compile module: both variants have the jar, the api variant leaves out the runtime-only dependency
        val api = parseModule(ivyDir, "api")
        assertEquals(1, fileUrls(variant(api, "apiElements")).size)
        assertEquals(listOf("lib"), depNames(variant(api, "apiElements")))
        assertEquals(listOf("lib", "rt"), depNames(variant(api, "runtimeElements")))

        // runtime-only module: nobody pulls it in at compile time, but a project that asks for exactly this module
        // (for example by declaring the same group:artifact:version itself) gets the jar and its dependencies
        val rt = parseModule(ivyDir, "rt")
        assertEquals(listOf("../../../../client/lib/rt-1.0.0.jar"), fileUrls(variant(rt, "apiElements")))
        assertEquals(listOf("../../../../client/lib/rt-1.0.0.jar"), fileUrls(variant(rt, "runtimeElements")))
        assertEquals(listOf("lib"), depNames(variant(rt, "apiElements")))

        // bundle: the compile classpath is the compile jars, the runtime classpath is everything
        val bundle = JsonSlurper().parse(ivyDir.walkTopDown().single { it.name == "client-2026.1.0.module" }) as Map<*, *>
        assertEquals(listOf("api", "lib"), depNames(variant(bundle, "apiElements")))
        assertEquals(listOf("api", "lib", "rt"), depNames(variant(bundle, "runtimeElements")))

        assertTrue(File(ivyDir, "vendor-layout.properties").readText().contains("compileSplit=true"))
    }

    @Test
    fun `slim jars are only made for compile modules`() {
        val vendorRoot = File(tempDir, "vendor")
        val ivyDir = File(vendorRoot, "ivy")
        for (name in listOf("api", "rt")) {
            File(vendorRoot, "client/lib/$name-1.0.0.jar").apply { parentFile.mkdirs() }
                .outputStream().use { java.util.jar.JarOutputStream(it).close() }
        }

        IvyRepositoryGenerator.generate(
            listOf(module("api"), module("rt", compile = false)), vendorRoot, ivyDir, "2026.1.0", "lv.brain.vendor.bundle", slimJars = true
        )

        assertEquals(listOf("api-1.0.0-slim.jar"), ivyDir.walkTopDown().filter { it.name.endsWith("-slim.jar") }.map { it.name }.toList())
        assertEquals(listOf("api-1.0.0-slim.jar"), fileUrls(variant(parseModule(ivyDir, "api"), "apiElements")))
        // the runtime-only module offers its original jar when it is named explicitly
        assertEquals(listOf("../../../../client/lib/rt-1.0.0.jar"), fileUrls(variant(parseModule(ivyDir, "rt"), "apiElements")))
    }

    @Test
    fun `layout marker says the distribution is not split when every module is a compile module`() {
        val vendorRoot = File(tempDir, "vendor")
        val ivyDir = File(vendorRoot, "ivy")

        IvyRepositoryGenerator.generate(listOf(module("alpha"), module("beta")), vendorRoot, ivyDir, "2026.1.0", "lv.brain.vendor.bundle", slimJars = false)

        assertTrue(File(ivyDir, "vendor-layout.properties").readText().contains("compileSplit=false"))
        // the api variant of an ordinary module is exactly what it was before the split existed
        assertEquals(1, fileUrls(variant(parseModule(ivyDir, "alpha"), "apiElements")).size)
    }
}
