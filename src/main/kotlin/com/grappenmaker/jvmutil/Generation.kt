package com.grappenmaker.jvmutil

import org.objectweb.asm.ClassVisitor
import org.objectweb.asm.ClassWriter
import org.objectweb.asm.MethodVisitor
import org.objectweb.asm.Opcodes
import org.objectweb.asm.Opcodes.INVOKESPECIAL
import org.objectweb.asm.util.TraceClassVisitor
import java.io.PrintWriter

// Everything related to class generation

/**
 * Generates a class with given [name] and loads it with the specified [loader] function
 *
 * @param [extends] internal name of the class to inherit from.
 * @param [implements] list of internal names of interfaces to inherit from/implement.
 * @param [defaultConstructor] determines if a simple ()V constructor with super() is generated.
 * be careful: if the class that gets extended doesn't have a ()V of its own, it'll fail.
 * @param [access] access flags for the new class.
 * @param [writerFlags] flags to pass to ClassWriter.
 * @param [version] Opcode that determines the class file version. Defaults to 1.8.
 * @param [loader] function that loads the generated class.
 * @param [debug] whether to print the output of the class generation.
 */
public inline fun generateClass(
    name: String,
    extends: String = "java/lang/Object",
    implements: List<String> = listOf(),
    defaultConstructor: Boolean = true,
    access: Int = Opcodes.ACC_PUBLIC or Opcodes.ACC_FINAL,
    writerFlags: Int = ClassWriter.COMPUTE_FRAMES,
    version: Int = Opcodes.V1_8,
    loader: (bytes: ByteArray, name: String) -> Class<*>,
    debug: Boolean = false,
    generator: ClassVisitor.() -> Unit
): Class<*> = loader(
    generateClassBytes(name, extends, implements, access, defaultConstructor, writerFlags, version, debug, generator),
    name.replace('/', '.')
)

/**
 * Generates a class with given [name]
 *
 * @param [extends] internal name of the class to inherit from.
 * @param [implements] list of internal names of interfaces to inherit from/implement.
 * @param [access] access flags for the new class.
 * @param [defaultConstructor] determines if a simple ()V constructor with super() is generated.
 * be careful: if the class that gets extended doesn't have a ()V of its own, it'll fail.
 * @param [writerFlags] flags to pass to ClassWriter.
 * @param [version] Opcode that determines the class file version. Defaults to 1.8.
 * @param [debug] whether to print the output of the class generation.
 */
public inline fun generateClassBytes(
    name: String,
    extends: String = "java/lang/Object",
    implements: List<String> = listOf(),
    access: Int = Opcodes.ACC_PUBLIC or Opcodes.ACC_FINAL,
    defaultConstructor: Boolean = access and Opcodes.ACC_INTERFACE == 0,
    writerFlags: Int = ClassWriter.COMPUTE_FRAMES,
    version: Int = Opcodes.V1_8,
    debug: Boolean = false,
    generator: ClassVisitor.() -> Unit
): ByteArray {
    val writer = ClassWriter(writerFlags)
    val visitor = if (debug) TraceClassVisitor(writer, PrintWriter(System.out)) else writer
    with(visitor) {
        visit(version, access, name, null, extends, implements.toTypedArray())

        if (defaultConstructor) {
            generateMethod("<init>", "()V") {
                loadThis()
                visitMethodInsn(INVOKESPECIAL, extends, "<init>", "()V", false)
                returnMethod()
            }
        }

        generator()
        visitEnd()
    }

    return writer.toByteArray()
}

/**
 * Generates a method with given [name] and [descriptor] for this [ClassVisitor] using the [generator]
 * Note that if [generator] is null, a no-code method will be generated
 *
 * @param [access] Opcodes that determine access for the new method
 * @param [maxStack] maximum stack size for the code block
 * @param [maxLocals] maximum local variable count (`this` counts!) for this method
 */
public fun ClassVisitor.generateMethod(
    name: String,
    descriptor: String,
    access: Int = Opcodes.ACC_PUBLIC,
    maxStack: Int = 0,
    maxLocals: Int = 0,
    generator: (MethodVisitor.() -> Unit)? = null
): Unit = with(visitMethod(access, name, descriptor, null, null)) {
    if (generator != null) {
        visitCode()
        generator()
        visitMaxs(maxStack, maxLocals)
    }

    visitEnd()
}