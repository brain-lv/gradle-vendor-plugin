/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 *
 * Copyright (c) 2026 Edgars Grinvalds <edgars.grinvalds@brain.lv>
 */

package lv.brain.gradle.distributor

import lv.brain.gradle.support.SyntheticJars
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.objectweb.asm.ClassWriter
import org.objectweb.asm.Opcodes
import java.io.File
import java.util.zip.ZipEntry

/** What one pass over a jar records: body references, public API references, annotation-only references, Java version. */
class RawReferenceScannerTest {

    @TempDir
    lateinit var tmp: File

    private fun service(): ByteArray {
        val cw = ClassWriter(ClassWriter.COMPUTE_MAXS)
        cw.visit(Opcodes.V11, Opcodes.ACC_PUBLIC, "com/acme/Service", null, "org/base/Base", arrayOf("org/api/Contract"))
        cw.visitAnnotation("Lorg/marker/Marked;", true).visitEnd()
        cw.visitField(Opcodes.ACC_PUBLIC, "model", "Lorg/model/Model;", null, null).visitEnd()
        cw.visitField(Opcodes.ACC_PRIVATE, "cache", "Lorg/cache/Cache;", null, null).visitEnd()
        cw.visitMethod(Opcodes.ACC_PUBLIC, "load", "(Ljava/lang/String;)Ljava/util/List;", "(Ljava/lang/String;)Ljava/util/List<Lorg/item/Item;>;", arrayOf("org/err/Failure"))
            .apply {
                visitCode()
                visitTypeInsn(Opcodes.NEW, "org/impl/Worker")
                visitInsn(Opcodes.POP)
                visitInsn(Opcodes.ACONST_NULL)
                visitInsn(Opcodes.ARETURN)
                visitMaxs(0, 0)
                visitEnd()
            }
        cw.visitMethod(Opcodes.ACC_PRIVATE, "helper", "(Lorg/hidden/Hidden;)V", null, null).apply {
            visitCode(); visitInsn(Opcodes.RETURN); visitMaxs(0, 0); visitEnd()
        }
        cw.visitEnd()
        return cw.toByteArray()
    }

    @Test
    fun `public signatures are API, method bodies and private members are body only, own and JDK classes are skipped`() {
        val jar = File(tmp, "service.jar")
        SyntheticJars.write(jar, mapOf(
            "com/acme/Service.class" to service(),
            "com/acme/Other.class" to SyntheticJars.clazz("com/acme/Other", bodyUses = "com/acme/Service"),
            "META-INF/versions/17/com/acme/Ignored.class" to SyntheticJars.clazz("com/acme/Ignored", bodyUses = "org/never/Seen")
        ))

        val raw = RawReferenceScanner.scan(jar, setOf("com/acme/Service", "com/acme/Other"))

        val api = raw.api.getValue("com/acme/Service").toSet()
        assertEquals(setOf("org/base/Base", "org/api/Contract", "org/model/Model", "org/item/Item", "org/err/Failure"), api)
        val body = raw.body.getValue("com/acme/Service").toSet()
        assertTrue(body.containsAll(api + setOf("org/impl/Worker", "org/cache/Cache", "org/hidden/Hidden")), body.toString())
        assertFalse(body.any { it.startsWith("java/") || it.startsWith("com/acme/") }, body.toString())
        assertEquals(listOf("org/marker/Marked"), raw.annotations["com/acme/Service"])
        assertFalse("com/acme/Other" in raw.body, "a reference to an own class is not recorded")
        assertFalse(raw.body.values.flatten().contains("org/never/Seen"), "META-INF classes are not scanned")
        assertEquals(17, raw.javaVersion)
        assertEquals(64, raw.sha256.length)
    }

    @Test
    fun `only real classes outside META-INF are scanned`() {
        assertTrue(RawReferenceScanner.isScannedClass(ZipEntry("a/B.class")))
        assertFalse(RawReferenceScanner.isScannedClass(ZipEntry("a/")))
        assertFalse(RawReferenceScanner.isScannedClass(ZipEntry("a/B.txt")))
        assertFalse(RawReferenceScanner.isScannedClass(ZipEntry("META-INF/versions/11/a/B.class")))
        assertFalse(RawReferenceScanner.isScannedClass(ZipEntry("module-info.class")))
    }
}
