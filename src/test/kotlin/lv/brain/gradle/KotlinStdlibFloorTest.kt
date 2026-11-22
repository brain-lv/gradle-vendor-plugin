/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 *
 * Copyright (c) 2026 Edgars Grinvalds <edgars.grinvalds@brain.lv>
 */

package lv.brain.gradle

import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.objectweb.asm.ClassReader
import org.objectweb.asm.ClassVisitor
import org.objectweb.asm.FieldVisitor
import org.objectweb.asm.Handle
import org.objectweb.asm.MethodVisitor
import org.objectweb.asm.Opcodes
import org.objectweb.asm.Type
import java.io.File
import java.util.zip.ZipFile

/**
 * The plugin runs on the Kotlin standard library of the Gradle that applies it, and Gradle 8.11 has Kotlin 2.0.20.
 * Every stdlib class, method and field the compiled plugin uses must exist there, or Gradle 8 fails with
 * NoSuchMethodError or NoClassDefFoundError.
 */
class KotlinStdlibFloorTest {

    private class Member(val owner: String, val name: String, val descriptor: String, val isField: Boolean)

    private class Floor(jar: File) {
        val supers = HashMap<String, List<String>>()
        val members = HashMap<String, MutableSet<String>>()

        init {
            ZipFile(jar).use { zip ->
                zip.entries().asSequence().filter { it.name.endsWith(".class") }.forEach { entry ->
                    val reader = ClassReader(zip.getInputStream(entry).use { it.readBytes() })
                    supers[reader.className] = listOfNotNull(reader.superName) + reader.interfaces
                    val own = members.getOrPut(reader.className) { HashSet() }
                    reader.accept(object : ClassVisitor(Opcodes.ASM9) {
                        override fun visitMethod(access: Int, name: String, descriptor: String, signature: String?, exceptions: Array<out String>?): MethodVisitor? {
                            own += "m $name$descriptor"
                            return null
                        }

                        override fun visitField(access: Int, name: String, descriptor: String, signature: String?, value: Any?): FieldVisitor? {
                            own += "f $name:$descriptor"
                            return null
                        }
                    }, ClassReader.SKIP_CODE)
                }
            }
        }

        fun has(member: Member): Boolean {
            val key = if (member.isField) "f ${member.name}:${member.descriptor}" else "m ${member.name}${member.descriptor}"
            val seen = HashSet<String>()
            val queue = ArrayDeque(listOf(member.owner))
            while (queue.isNotEmpty()) {
                val type = queue.removeFirst()
                if (!seen.add(type)) continue
                if (key in members[type].orEmpty()) return true
                if (!type.startsWith("kotlin/")) return true
                queue.addAll(supers[type].orEmpty())
            }
            return false
        }
    }

    @Test
    fun `the compiled plugin uses only what the Kotlin standard library of Gradle 8_11 has`() {
        val floorJar = File(System.getProperty("vendor.kotlinStdlibFloor") ?: error("run through Gradle: vendor.kotlinStdlibFloor is not set"))
        val classDirs = System.getProperty("vendor.mainClasses").split(File.pathSeparator).map(::File).filter { it.isDirectory }
        val floor = Floor(floorJar)

        val types = HashMap<String, MutableSet<String>>()
        val members = ArrayList<Pair<Member, String>>()
        fun type(internalName: String, from: String) {
            val name = Type.getObjectType(internalName).let { if (it.sort == Type.ARRAY) it.elementType.internalName else internalName }
            if (name.startsWith("kotlin/")) types.getOrPut(name) { HashSet() } += from
        }
        fun descriptor(desc: String, from: String) {
            Regex("L([^;<]+);").findAll(desc).forEach { type(it.groupValues[1], from) }
        }

        var classCount = 0
        classDirs.flatMap { dir -> dir.walkTopDown().filter { it.isFile && it.name.endsWith(".class") }.toList() }.forEach { file ->
            classCount++
            val reader = ClassReader(file.readBytes())
            val from = reader.className.replace('/', '.')
            reader.superName?.let { type(it, from) }
            reader.interfaces.forEach { type(it, from) }
            reader.accept(object : ClassVisitor(Opcodes.ASM9) {
                override fun visitField(access: Int, name: String, desc: String, signature: String?, value: Any?): FieldVisitor? {
                    descriptor(desc, from)
                    return null
                }

                override fun visitMethod(access: Int, name: String, desc: String, signature: String?, exceptions: Array<out String>?): MethodVisitor {
                    descriptor(desc, from)
                    return object : MethodVisitor(Opcodes.ASM9) {
                        override fun visitMethodInsn(opcode: Int, owner: String, name: String, desc: String, isInterface: Boolean) {
                            type(owner, from)
                            descriptor(desc, from)
                            if (owner.startsWith("kotlin/")) members += Member(owner, name, desc, false) to from
                        }

                        override fun visitFieldInsn(opcode: Int, owner: String, name: String, desc: String) {
                            type(owner, from)
                            descriptor(desc, from)
                            if (owner.startsWith("kotlin/")) members += Member(owner, name, desc, true) to from
                        }

                        override fun visitTypeInsn(opcode: Int, type: String) = type(type, from)

                        override fun visitLdcInsn(value: Any?) {
                            if (value is Type && value.sort == Type.OBJECT) type(value.internalName, from)
                        }

                        override fun visitInvokeDynamicInsn(name: String, desc: String, bsm: Handle, vararg args: Any?) {
                            descriptor(desc, from)
                            args.filterIsInstance<Handle>().filter { it.owner.startsWith("kotlin/") }
                                .forEach { members += Member(it.owner, it.name, it.desc, it.tag <= Opcodes.H_PUTSTATIC) to from }
                        }
                    }
                }
            }, 0)
        }

        val missingTypes = types.filterKeys { it !in floor.supers }
        val missingMembers = members.filter { (member, _) -> member.owner in floor.supers && !floor.has(member) }
            .groupBy({ (m, _) -> "${m.owner.replace('/', '.')}.${m.name}${if (m.isField) ":" else ""}${m.descriptor}" }, { it.second })
        val problems = missingTypes.map { (t, from) -> "class ${t.replace('/', '.')} (used by ${from.sorted().take(3).joinToString()})" } +
            missingMembers.map { (m, from) -> "$m (used by ${from.distinct().sorted().take(3).joinToString()})" }

        assertTrue(classCount > 50, "found only $classCount compiled classes in $classDirs")
        assertTrue(
            problems.isEmpty(),
            "The plugin uses Kotlin stdlib API that Gradle 8.11 (Kotlin 2.0.20) does not have; it would fail there with " +
                "NoSuchMethodError or NoClassDefFoundError:\n" + problems.sorted().joinToString("\n") { "  $it" }
        )
    }
}
