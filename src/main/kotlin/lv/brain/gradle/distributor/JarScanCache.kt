/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 *
 * Copyright (c) 2026 Edgars Grinvalds <edgars.grinvalds@brain.lv>
 */

package lv.brain.gradle.distributor

import org.objectweb.asm.AnnotationVisitor
import org.objectweb.asm.ClassReader
import org.objectweb.asm.ClassVisitor
import org.objectweb.asm.FieldVisitor
import org.objectweb.asm.MethodVisitor
import org.objectweb.asm.Opcodes
import org.objectweb.asm.RecordComponentVisitor
import org.objectweb.asm.TypePath
import org.objectweb.asm.Type
import org.objectweb.asm.commons.ClassRemapper
import org.objectweb.asm.commons.Remapper
import org.objectweb.asm.signature.SignatureReader
import org.objectweb.asm.signature.SignatureVisitor
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.TreeMap
import java.util.TreeSet
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import java.util.jar.JarFile
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream

internal class RawReferences(
    val body: Map<String, List<String>>,
    val api: Map<String, List<String>>,
    val annotations: Map<String, List<String>> = emptyMap(),
    val classVersion: Int = 0,
    val sha256: String = ""
) {
    val javaVersion: Int? get() = if (classVersion >= 45) maxOf(1, classVersion - 44) else null
}

internal object RawReferenceScanner {
    fun scan(file: File, ownClasses: Set<String>): RawReferences {
        val collector = ClassReferenceCollector(ownClasses)
        JarFile(file).use { jar ->
            jar.entries().asSequence().filter(::isScannedClass).forEach { entry ->
                jar.getInputStream(entry).use { stream -> collector.read(entry.name.removeSuffix(".class"), stream) }
            }
        }
        return collector.result(sha256(file))
    }

    internal fun isScannedClass(entry: java.util.zip.ZipEntry): Boolean =
        !entry.isDirectory && entry.name.endsWith(".class") && !entry.name.startsWith("META-INF/") && entry.name != "module-info.class"

    fun sha256(file: File): String {
        val digest = java.security.MessageDigest.getInstance("SHA-256")
        file.inputStream().buffered(64 * 1024).use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }
}

internal class ClassReferenceCollector(private val ownClasses: Set<String>) {
    private val body = TreeMap<String, TreeSet<String>>()
    private val api = TreeMap<String, TreeSet<String>>()
    private val annotations = TreeMap<String, TreeSet<String>>()
    private var classVersion = 0
    private var currentBody = TreeSet<String>()
    private var currentApi = TreeSet<String>()
    private var currentAnnotations = TreeSet<String>()
    private var apiClass = false
    private val visitor = ClassRemapper(ApiSink(), ForeignRemapper())

    fun read(className: String, stream: java.io.InputStream) {
        currentBody = TreeSet()
        currentApi = TreeSet()
        currentAnnotations = TreeSet()
        ClassReader(stream).accept(visitor, ClassReader.SKIP_DEBUG or ClassReader.SKIP_FRAMES)
        if (currentBody.isNotEmpty()) body.getOrPut(className) { TreeSet() }.addAll(currentBody)
        if (currentApi.isNotEmpty()) api.getOrPut(className) { TreeSet() }.addAll(currentApi)
        currentAnnotations.retainAll { it in currentBody }
        if (currentAnnotations.isNotEmpty()) annotations.getOrPut(className) { TreeSet() }.addAll(currentAnnotations)
    }

    fun result(sha256: String): RawReferences = RawReferences(
        body.mapValues { it.value.toList() }, api.mapValues { it.value.toList() }, annotations.mapValues { it.value.toList() },
        classVersion, sha256
    )

    private fun foreign(name: String?): Boolean = name != null && name !in ownClasses && !name.startsWith("java/")

    private fun noteAnnotation(descriptor: String?) {
        val type = descriptor?.let { runCatching { Type.getType(it) }.getOrNull() } ?: return
        if (type.sort == Type.OBJECT) currentAnnotations.add(type.internalName)
    }

    private fun noteApi(name: String?) {
        if (apiClass && name != null && foreign(name)) currentApi.add(name)
    }

    private fun noteType(type: Type) {
        val element = if (type.sort == Type.ARRAY) type.elementType else type
        if (element.sort == Type.OBJECT) noteApi(element.internalName)
    }

    private fun noteDescriptor(descriptor: String) {
        val type = Type.getType(descriptor)
        if (type.sort == Type.METHOD) {
            type.argumentTypes.forEach(::noteType)
            noteType(type.returnType)
        } else {
            noteType(type)
        }
    }

    private val signatureCollector = object : SignatureVisitor(Opcodes.ASM9) {
        override fun visitClassType(name: String) = noteApi(name)
    }

    private fun noteSignature(signature: String?) {
        if (signature != null) SignatureReader(signature).accept(signatureCollector)
    }

    private fun isApiMember(access: Int) =
        access and (Opcodes.ACC_PUBLIC or Opcodes.ACC_PROTECTED) != 0 && access and Opcodes.ACC_SYNTHETIC == 0

    private inner class ForeignRemapper : Remapper(Opcodes.ASM9) {
        override fun map(internalName: String): String {
            if (foreign(internalName)) currentBody.add(internalName)
            return internalName
        }
    }

    private inner class FieldAnnotations : FieldVisitor(Opcodes.ASM9) {
        override fun visitAnnotation(descriptor: String?, visible: Boolean): AnnotationVisitor? = null.also { noteAnnotation(descriptor) }
        override fun visitTypeAnnotation(typeRef: Int, typePath: TypePath?, descriptor: String?, visible: Boolean): AnnotationVisitor? =
            null.also { noteAnnotation(descriptor) }
    }

    private inner class MethodAnnotations : MethodVisitor(Opcodes.ASM9) {
        override fun visitAnnotation(descriptor: String?, visible: Boolean): AnnotationVisitor? = null.also { noteAnnotation(descriptor) }
        override fun visitParameterAnnotation(parameter: Int, descriptor: String?, visible: Boolean): AnnotationVisitor? =
            null.also { noteAnnotation(descriptor) }
        override fun visitTypeAnnotation(typeRef: Int, typePath: TypePath?, descriptor: String?, visible: Boolean): AnnotationVisitor? =
            null.also { noteAnnotation(descriptor) }
        override fun visitInsnAnnotation(typeRef: Int, typePath: TypePath?, descriptor: String?, visible: Boolean): AnnotationVisitor? =
            null.also { noteAnnotation(descriptor) }
        override fun visitTryCatchAnnotation(typeRef: Int, typePath: TypePath?, descriptor: String?, visible: Boolean): AnnotationVisitor? =
            null.also { noteAnnotation(descriptor) }
        override fun visitLocalVariableAnnotation(
            typeRef: Int, typePath: TypePath?, start: Array<out org.objectweb.asm.Label>?, end: Array<out org.objectweb.asm.Label>?,
            index: IntArray?, descriptor: String?, visible: Boolean
        ): AnnotationVisitor? = null.also { noteAnnotation(descriptor) }
    }

    private inner class ApiSink : ClassVisitor(Opcodes.ASM9) {
        private val fieldSink = FieldAnnotations()
        private val methodSink = MethodAnnotations()

        override fun visit(version: Int, access: Int, name: String, signature: String?, superName: String?, interfaces: Array<out String>?) {
            classVersion = maxOf(classVersion, version and 0xFFFF)
            apiClass = access and Opcodes.ACC_PUBLIC != 0 && !name.endsWith("/package-info") && name != "package-info"
            noteApi(superName)
            interfaces?.forEach { noteApi(it) }
            noteSignature(signature)
        }

        override fun visitField(access: Int, name: String, descriptor: String, signature: String?, value: Any?): FieldVisitor {
            if (isApiMember(access)) {
                noteDescriptor(descriptor)
                noteSignature(signature)
            }
            return fieldSink
        }

        override fun visitAnnotation(descriptor: String?, visible: Boolean): AnnotationVisitor? = null.also { noteAnnotation(descriptor) }

        override fun visitTypeAnnotation(typeRef: Int, typePath: TypePath?, descriptor: String?, visible: Boolean): AnnotationVisitor? =
            null.also { noteAnnotation(descriptor) }

        override fun visitMethod(access: Int, name: String, descriptor: String, signature: String?, exceptions: Array<out String>?): MethodVisitor {
            if (isApiMember(access)) {
                noteDescriptor(descriptor)
                noteSignature(signature)
                exceptions?.forEach { noteApi(it) }
            }
            return methodSink
        }

        override fun visitRecordComponent(name: String?, descriptor: String?, signature: String?): RecordComponentVisitor =
            object : RecordComponentVisitor(Opcodes.ASM9) {}
    }
}

internal class JarScanCache(private val dir: File?) {
    private val used: MutableSet<String> = ConcurrentHashMap.newKeySet()
    private val memory = ConcurrentHashMap<String, RawReferences>()
    val scanned = AtomicInteger()
    val reused = AtomicInteger()

    fun references(file: File, contentKey: String, definedClasses: Set<String>): RawReferences {
        val key = contentKey.ifEmpty { "path-" + file.absolutePath.hashCode().toUInt().toString(16) }
        used += key
        memory[key]?.let { return it }
        return memory.putIfAbsent(key, load(file, definedClasses, key)) ?: memory.getValue(key)
    }

    private fun load(file: File, definedClasses: Set<String>, key: String): RawReferences {
        val stored = dir?.let { File(it, "$key$SUFFIX") }
        if (stored != null && stored.isFile) {
            runCatching { read(stored) }.getOrNull()?.let {
                reused.incrementAndGet()
                return it
            }
        }
        val raw = RawReferenceScanner.scan(file, definedClasses)
        scanned.incrementAndGet()
        if (stored != null) runCatching { write(stored, raw) }
        return raw
    }

    fun prune() {
        dir?.listFiles()?.filter { it.isFile && it.name.removeSuffix(SUFFIX) !in used }?.forEach { java.nio.file.Files.deleteIfExists(it.toPath()) }
    }

    private fun write(target: File, raw: RawReferences) {
        target.parentFile.mkdirs()
        val temp = File(target.parentFile, "${target.name}.${Thread.currentThread().id}.tmp")
        DataOutputStream(GZIPOutputStream(temp.outputStream().buffered())).use { out ->
            out.writeInt(FORMAT)
            out.writeInt(raw.classVersion)
            out.writeUTF(raw.sha256)
            for (section in listOf(raw.body, raw.api, raw.annotations)) {
                out.writeInt(section.size)
                for ((from, targets) in section) {
                    out.writeUTF(from)
                    out.writeInt(targets.size)
                    targets.forEach(out::writeUTF)
                }
            }
        }
        Files.move(temp.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
    }

    private fun read(file: File): RawReferences =
        DataInputStream(GZIPInputStream(file.inputStream().buffered())).use { input ->
            check(input.readInt() == FORMAT)
            val classVersion = input.readInt()
            val sha256 = input.readUTF()
            fun section(): Map<String, List<String>> {
                val result = LinkedHashMap<String, List<String>>()
                repeat(input.readInt()) {
                    val from = input.readUTF()
                    result[from] = List(input.readInt()) { input.readUTF() }
                }
                return result
            }
            val body = section()
            val api = section()
            RawReferences(body, api, section(), classVersion, sha256)
        }

    companion object {
        private const val FORMAT = 2
        private const val SUFFIX = ".refs"
    }
}
