/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 *
 * Copyright (c) 2026 Edgars Grinvalds <edgars.grinvalds@brain.lv>
 */

package lv.brain.gradle.distributor

import groovy.json.JsonSlurper
import lv.brain.gradle.core.VendorSignature
import lv.brain.gradle.support.SyntheticJars
import org.gradle.api.GradleException
import org.gradle.testfixtures.ProjectBuilder
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.objectweb.asm.ClassWriter
import org.objectweb.asm.Opcodes
import java.io.File
import java.security.KeyPairGenerator

class ReleaseSafetyTest {

    @TempDir
    lateinit var tempDir: File

    private val vendorDir get() = File(tempDir, "vendor")

    private fun moduleInfo(name: String): ByteArray {
        val cw = ClassWriter(0)
        cw.visit(Opcodes.V17, Opcodes.ACC_MODULE, "module-info", null, null, null)
        cw.visitModule(name, 0, null).visitEnd()
        cw.visitEnd()
        return cw.toByteArray()
    }

    private fun manifest(automaticName: String) =
        "META-INF/MANIFEST.MF" to "Manifest-Version: 1.0\r\nAutomatic-Module-Name: $automaticName\r\n\r\n".toByteArray()

    private fun cls(name: String) = "$name.class" to SyntheticJars.clazz(name)

    @Suppress("UNCHECKED_CAST")
    private fun resolve(jpms: String = "warn"): Map<String, Any?> {
        val project = ProjectBuilder.builder().withProjectDir(tempDir).build()
        val graph = File(tempDir, "build/module-dependencies.json")
        project.tasks.register("resolveDeps", ResolveModuleDependenciesTask::class.java) {
            this.vendorDir.set(this@ReleaseSafetyTest.vendorDir)
            this.dependencyGraphFile.set(graph)
            this.jpmsCheck.set(jpms)
        }.get().resolveDependencies()
        return JsonSlurper().parse(graph) as Map<String, Any?>
    }

    @Test
    fun `a signature made with an Ed25519 or RSA key is accepted only for the unchanged file`() {
        val zip = File(tempDir, "vendor-sdk-1.zip").apply { writeText("distribution") }
        val (privatePem, publicPem) = VendorSignature.generate()
        val signature = VendorSignature.sign(zip, VendorSignature.privateKey(privatePem))
        assertTrue(VendorSignature.verify(zip, signature, VendorSignature.publicKey(publicPem)))
        val oneLine = java.util.Base64.getEncoder().encodeToString(VendorSignature.der(publicPem))
        assertTrue(VendorSignature.verify(zip, signature, VendorSignature.publicKey(oneLine)))

        val rsa = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
        val rsaSignature = VendorSignature.sign(zip, VendorSignature.privateKey(VendorSignature.pem("PRIVATE KEY", rsa.private.encoded)))
        assertTrue(VendorSignature.verify(zip, rsaSignature, VendorSignature.publicKey(VendorSignature.pem("PUBLIC KEY", rsa.public.encoded))))

        zip.writeText("distribution, changed")
        assertFalse(VendorSignature.verify(zip, signature, VendorSignature.publicKey(publicPem)))
        val sigFile = File(zip.path + ".sig").apply { writeText(signature) }
        val changed = assertThrows(GradleException::class.java) { VendorSignature.requireValid(zip, sigFile, publicPem) }
        assertTrue("does not match vendor.signing.publicKey" in changed.message.orEmpty(), changed.message)
        val missing = assertThrows(GradleException::class.java) { VendorSignature.requireValid(zip, File(tempDir, "none.sig"), publicPem) }
        assertTrue("has no signature" in missing.message.orEmpty(), missing.message)
    }

    @Test
    fun `jars that would break on the module path are reported, and fail on request`() {
        SyntheticJars.write(File(vendorDir, "a-1.0.jar"), mapOf("module-info.class" to moduleInfo("com.acme.core"), cls("com/acme/A")))
        SyntheticJars.write(File(vendorDir, "b-1.0.jar"), mapOf(manifest("com.acme.core"), cls("com/acme/B")))
        SyntheticJars.write(File(vendorDir, "c-1.0.jar"), mapOf(manifest("com.acme.extra"), cls("com/acme/C")))
        SyntheticJars.write(File(vendorDir, "plain-1.0.jar"), mapOf(cls("org/plain/P"), cls("com/acme/D")))

        val json = resolve()

        @Suppress("UNCHECKED_CAST")
        val modules = (json["modules"] as List<Map<String, Any?>>).associateBy { it["moduleId"] }
        assertEquals("explicit", modules.getValue("a")["moduleKind"])
        assertEquals("com.acme.extra", modules.getValue("c")["moduleName"])
        assertEquals("none", modules.getValue("plain")["moduleKind"])
        @Suppress("UNCHECKED_CAST")
        val jpms = json["jpms"] as Map<String, Any?>
        assertEquals(listOf(mapOf("moduleName" to "com.acme.core", "jars" to listOf("a", "b"))), jpms["duplicateModuleNames"])
        assertEquals(
            listOf(mapOf("package" to "com.acme", "jars" to listOf("a", "b", "c", "plain"), "betweenNamedModules" to true)),
            jpms["splitPackages"]
        )
        val failure = assertThrows(GradleException::class.java) { resolve(jpms = "fail") }
        assertTrue("com.acme.core: a, b" in failure.message.orEmpty() && "vendor.jpmsCheck=off" in failure.message.orEmpty(), failure.message)
    }
}
