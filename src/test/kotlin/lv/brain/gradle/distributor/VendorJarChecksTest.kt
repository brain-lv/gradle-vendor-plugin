/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 *
 * Copyright (c) 2026 Edgars Grinvalds <edgars.grinvalds@brain.lv>
 */

package lv.brain.gradle.distributor

import lv.brain.gradle.core.ClassExcludes
import lv.brain.gradle.core.LicensePolicy
import lv.brain.gradle.distributor.VendorIvyDependencyMapper.ResolvedDep
import lv.brain.gradle.distributor.VendorIvyDependencyMapper.ResolvedModule
import org.gradle.api.GradleException
import org.gradle.api.logging.Logging
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.io.File
import java.util.TreeSet

/** The checks of the resolve task and of verifyVendorAgainstMaven, without a Gradle build. */
class VendorJarChecksTest {

    private val logger = Logging.getLogger(VendorJarChecksTest::class.java)

    private fun jar(id: String, classes: Set<String>, moduleName: String? = null, identity: String = VendorJarScanner.IDENTITY_POM_PROPERTIES) =
        JarMetadata(id, "g", id, "1.0", File("$id.jar"), null, false, classes, listOf("core"), moduleName = moduleName, identity = identity)

    @Test
    fun `module path problems name duplicate module names and packages split between named modules`() {
        val checks = VendorJarChecks(
            listOf(
                jar("a", setOf("p/A"), moduleName = "same"),
                jar("b", setOf("p/B"), moduleName = "same"),
                jar("c", setOf("q/C"), moduleName = "c"),
                jar("d", setOf("q/D")),
                jar("e", setOf("r/E"), moduleName = "e"),
                jar("f", setOf("r/F"), moduleName = "f")
            ),
            emptySet(), logger
        )

        val json = checks.checkModulePath(MISSING_WARN)

        val split = (json["splitPackages"] as List<*>).map { it as Map<*, *> }.associate { it["package"] to it["betweenNamedModules"] }
        assertEquals(mapOf("p" to true, "q" to false, "r" to true), split)
        val failure = assertThrows<GradleException> { checks.checkModulePath(MISSING_FAIL) }
        assertTrue("1 module name(s) are used by more than one jar (same: a, b)" in failure.message!!, failure.message)
        assertTrue("2 package(s) are split between named modules (p in a, b; r in e, f)" in failure.message!!, failure.message)
    }

    @Test
    fun `module path messages list three examples and say when there are more`() {
        val checks = VendorJarChecks(emptyList(), emptySet(), logger)
        assertNull(checks.modulePathProblems(emptyMap(), emptyMap()))

        val names = (1..4).associate { "m$it" to listOf("x$it", "y$it") }
        val message = checks.modulePathProblems(names, emptyMap())!!
        assertTrue("4 module name(s) are used by more than one jar (m1: x1, y1; m2: x2, y2; m3: x3, y3; ...)" in message, message)
    }

    @Test
    fun `missing classes are grouped per package, JDK and excluded classes are not missing, open-source-only packages are optional`() {
        val app = jar("app", setOf("com/app/Main"), identity = VendorJarScanner.IDENTITY_FILE_NAME)
        val lib = jar("lib", setOf("org/lib/Lib"))
        val references = mapOf(
            "app" to RawReferences(
                body = mapOf("com/app/Main" to listOf("org/lib/Lib", "java/lang/String", "org/gone/Gone", "org/skip/Skipped", "org/ann/Only")),
                api = emptyMap(),
                annotations = mapOf("com/app/Main" to listOf("org/ann/Only"))
            ),
            "lib" to RawReferences(body = mapOf("org/lib/Lib" to listOf("org/optional/Extra")), api = emptyMap())
        )
        val checks = VendorJarChecks(listOf(app, lib), emptySet(), logger)

        val result = checks.findMissingClasses(ClassExcludes(listOf("org.skip")), { references.getValue(it.moduleId) }, MISSING_WARN, SCOPE_VENDOR)

        val byPackage = result.associateBy { it["package"] }
        assertEquals(setOf("org.gone", "org.optional"), byPackage.keys)
        assertEquals(false, byPackage.getValue("org.gone")["openSourceOnly"])
        assertEquals(true, byPackage.getValue("org.optional")["openSourceOnly"])
        val failure = assertThrows<GradleException> {
            checks.findMissingClasses(ClassExcludes(listOf("org.skip")), { references.getValue(it.moduleId) }, MISSING_FAIL, SCOPE_VENDOR)
        }
        assertTrue("1 class(es) in 1 package(s) are used by vendor jars" in failure.message!!, failure.message)
        val all = assertThrows<GradleException> {
            checks.findMissingClasses(ClassExcludes(listOf("org.skip")), { references.getValue(it.moduleId) }, MISSING_FAIL, SCOPE_ALL)
        }
        assertTrue("2 class(es) in 2 package(s)" in all.message!!, all.message)
    }

    @Test
    fun `the missing class message shortens long lists`() {
        val checks = VendorJarChecks(emptyList(), emptySet(), logger)
        val packages = (1..6).associate { n ->
            "p$n" to VendorJarChecks.MissingPackage(TreeSet(listOf("p$n/A")), TreeSet((1..4).map { "user$it" }))
        } + ("" to VendorJarChecks.MissingPackage(TreeSet(listOf("Root")), TreeSet(listOf("u"))))

        val message = checks.missingMessage(packages.toSortedMap())

        assertTrue("(default package) (1 class(es), used by u)" in message, message)
        assertTrue("used by user1, user2, user3, ..." in message, message)
        assertTrue("; ...." in message, message)
    }

    private fun module(id: String, vararg deps: String, licenses: List<String> = emptyList()) =
        ResolvedModule(id, "g", id, "1.0", "$id.jar", "maven-pom", deps.map { ResolvedDep(it, "g", it, "1.0") }, licenses = licenses)

    @Test
    fun `Maven findings compare published dependencies with the graph and apply the licence policy`() {
        val modules = listOf(module("app", "util", "extra"), module("util"), module("extra"), module("json", licenses = listOf("GPL-3.0")))
        val results = listOf(
            VerifyVendorAgainstMavenTask.MavenResult(modules[0], "g:app:1.0", VerifyVendorAgainstMavenTask.MATCH, "", setOf("g:util", "g:json")),
            VerifyVendorAgainstMavenTask.MavenResult(modules[1], "g:util:1.0", VerifyVendorAgainstMavenTask.MATCH, "", emptySet()),
            VerifyVendorAgainstMavenTask.MavenResult(modules[3], "g:json:1.0", VerifyVendorAgainstMavenTask.DIFFERS, "")
        )

        val dependencies = MavenCheckFindings.dependencyFindings(modules, results)
        assertEquals(listOf(mapOf("module" to "app", "missing" to listOf("json"), "extra" to listOf("extra"))), dependencies)

        val licences = MavenCheckFindings.licenseFindings(results, LicensePolicy(deny = listOf("GPL-*")))
        assertEquals(listOf(mapOf("module" to "json", "licenses" to listOf("GPL-3.0"))), licences)
        assertEquals(emptyList<Any>(), MavenCheckFindings.licenseFindings(results, LicensePolicy()))

        val counts = mapOf(VerifyVendorAgainstMavenTask.DIFFERS to 1, VerifyVendorAgainstMavenTask.NOT_FOUND to 2)
        assertEquals(
            listOf("1 jar(s) differ from the published ones", "2 jar(s) are not in the repository",
                "1 published dependency(ies) are not in the graph", "1 jar(s) have a licence the policy does not accept"),
            MavenCheckFindings.failures(setOf("differs", "notfound", "dependencies", "licenses"), counts, 1, 1)
        )
        assertEquals(emptyList<String>(), MavenCheckFindings.failures(emptySet(), counts, 1, 1))
    }
}
