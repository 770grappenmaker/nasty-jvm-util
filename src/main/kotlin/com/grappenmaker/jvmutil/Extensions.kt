package com.grappenmaker.jvmutil

import org.objectweb.asm.ClassReader
import org.objectweb.asm.Opcodes.*
import org.objectweb.asm.Type
import org.objectweb.asm.commons.AnalyzerAdapter
import org.objectweb.asm.tree.*
import org.objectweb.asm.util.TraceClassVisitor
import java.io.ByteArrayOutputStream
import java.io.PrintWriter
import java.lang.instrument.Instrumentation
import kotlin.reflect.KClass

/**
 * Yields the internal name of this [Class]
 */
public val Class<*>.internalName: String get() = name.replace('.', '/')

/**
 * Yields the internal name of this [KClass]
 */
public val KClass<*>.internalName: String get() = java.internalName

/**
 * Yields the internal name of [T]
 */
public inline fun <reified T> internalNameOf(): String = T::class.internalName

/**
 * Yields the resource name of this [Class]
 */
public val Class<*>.resourceName: String get() = "${internalName}.class"

/**
 * Yields the resource name of this [KClass]
 */
public val KClass<*>.resourceName: String get() = java.resourceName

/**
 * Yields the resource name of [T]
 */
public inline fun <reified T> resourceNameOf(): String = T::class.resourceName

/**
 * Converts a class name represented by this [String] to a resource name
 */
public val String.classResourceName: String get() = "${replace('.', '/')}.class"

/**
 * Gets the first occurrence of a method named [name] in this [ClassNode]
 */
public fun ClassNode.methodByName(name: String): MethodNode? = methods.find { it.name == name }

/**
 * Gets the first occurrence of a method described by a [MethodInsnNode] in this [ClassNode]
 */
public fun ClassNode.methodByInvoke(insn: MethodInsnNode): MethodNode? =
    methods.find { it.name == insn.name && it.desc == insn.desc }

/**
 * Finds the static initializer of this [ClassNode]
 */
public val ClassNode.initializer: MethodNode? get() = methodByName("<clinit>")

/**
 * Gets the first occurrence of a field named [name] in this [ClassNode]
 */
public fun ClassNode.fieldByName(name: String): FieldNode? = fields.find { it.name == name }

/**
 * Finds all constant pool values of this [ClassNode]
 */
public val ClassNode.constants: List<Any> get() = methods.flatMap { it.constants } + fields.mapNotNull { it.value }

/**
 * Finds all strings of this [ClassNode]
 */
public val ClassNode.strings: List<String> get() = constants.filterIsInstance<String>()

/**
 * Finds all constant pool values of this [MethodNode]
 */
public val MethodNode.constants: List<Any>
    get() = instructions.filterIsInstance<LdcInsnNode>().map { it.cst } +
            instructions.filterIsInstance<InvokeDynamicInsnNode>().flatMap { it.bsmArgs.asIterable() }

/**
 * Finds all strings of this [MethodNode]
 */
public val MethodNode.strings: List<String> get() = constants.filterIsInstance<String>()

/**
 * Checks if this [ClassNode] has a constant value of [cst]
 */
public fun ClassNode.hasConstant(cst: Any): Boolean = methods.any { it.hasConstant(cst) }

/**
 * Checks if this [MethodNode] has a constant value of [cst]
 */
public fun MethodNode.hasConstant(cst: Any): Boolean = constants.contains(cst)

/**
 * Equivalent to [hasConstant]
 */
public operator fun MethodNode.contains(cst: Any): Boolean = hasConstant(cst)

/**
 * Finds all method invocations (excluding `invokedynamic`) in this [MethodNode]
 */
public val MethodNode.calls: List<MethodInsnNode> get() = instructions.filterIsInstance<MethodInsnNode>()

/**
 * Checks if this [MethodNode] calls a method that matches [matcher]
 */
public fun MethodNode.calls(matcher: (MethodInsnNode) -> Boolean): Boolean = calls.any(matcher)

/**
 * Checks if this [MethodNode] calls a method that is named [name]
 */
public fun MethodNode.callsNamed(name: String): Boolean = calls { it.name == name }

/**
 * Finds all field references in this [MethodNode]
 */
public val MethodNode.references: List<FieldInsnNode> get() = instructions.filterIsInstance<FieldInsnNode>()

/**
 * Checks if this [MethodNode] references a field that matches [matcher]
 */
public fun MethodNode.references(matcher: (FieldInsnNode) -> Boolean): Boolean = references.any(matcher)

/**
 * Checks if this [MethodNode] references a field that is named [name]
 */
public fun MethodNode.referencesNamed(name: String): Boolean = references { it.name == name }

/**
 * Checks if this [MethodNode] returns a value described by the passed descriptor [toReturn]
 */
public fun MethodNode.returns(toReturn: String): Boolean = Type.getReturnType(desc).descriptor == toReturn

/**
 * All package names/class name prefixes to ignore when finding app/Minecraft related classes
 */
private val systemClasses = setOf(
    "java.", "javax.", "org.xml.", "org.w3c.", "sun.", "jdk.", "com.sun.management.",
    "com.grappenmaker.jvmutil.", "kotlin.", "kotlinx.", "org.objectweb.",
)

/**
 * Returns if a binary classname is a system class (see [systemClasses])
 */
public fun isSystemClass(className: String): Boolean = systemClasses.any { className.startsWith(it) }

/**
 * Returns all loaded classes by this JVM given by this [Instrumentation] that:
 * - Are not loaded by the Bootstrap ClassLoader
 * - Are not Array Classes
 * - Are not a system class
 *
 * @see systemClasses
 */
public fun Instrumentation.getAppClasses(): List<Class<*>> = allLoadedClasses.filter { c ->
    c.classLoader != null &&
            !c.isArray &&
            !isSystemClass(c.name) &&
            !c.name.contains("\$\$Lambda")
}

/**
 * Whether the method is `public`
 */
public val MethodNode.isPublic: Boolean get() = access and ACC_PUBLIC != 0

/**
 * Whether the method is `private`
 */
public val MethodNode.isPrivate: Boolean get() = access and ACC_PRIVATE != 0

/**
 * Whether the method is `protected`
 */
public val MethodNode.isProtected: Boolean get() = access and ACC_PROTECTED != 0

/**
 * Whether the method is `static`
 */
public val MethodNode.isStatic: Boolean get() = access and ACC_STATIC != 0

/**
 * Whether the method is `final`
 */
public val MethodNode.isFinal: Boolean get() = access and ACC_FINAL != 0

/**
 * Whether the method is `abstract`
 */
public val MethodNode.isAbstract: Boolean get() = access and ACC_ABSTRACT != 0

/**
 * Whether the method is a constructor
 */
public val MethodNode.isConstructor: Boolean get() = name == "<init>"

/**
 * Whether the field is `public`
 */
public val FieldNode.isPublic: Boolean get() = access and ACC_PUBLIC != 0

/**
 * Whether the field is `private`
 */
public val FieldNode.isPrivate: Boolean get() = access and ACC_PRIVATE != 0

/**
 * Whether the field is `static`
 */
public val FieldNode.isStatic: Boolean get() = access and ACC_STATIC != 0

/**
 * Whether the field is `final`
 */
public val FieldNode.isFinal: Boolean get() = access and ACC_FINAL != 0

/**
 * Whether the class is not a class, but an interface
 */
public val ClassNode.isInterface: Boolean get() = access and ACC_INTERFACE != 0

/**
 * Whether the class is `public`
 */
public val ClassNode.isPublic: Boolean get() = access and ACC_PUBLIC != 0

/**
 * Whether the class is `private`
 */
public val ClassNode.isPrivate: Boolean get() = access and ACC_PRIVATE != 0

/**
 * Whether the class is `static`
 */
public val ClassNode.isStatic: Boolean get() = access and ACC_STATIC != 0

/**
 * Whether the class is `final`
 */
public val ClassNode.isFinal: Boolean get() = access and ACC_FINAL != 0

/**
 * Whether the class is `abstract`
 */
public val ClassNode.isAbstract: Boolean get() = access and ACC_ABSTRACT != 0

/**
 * Yields a [Type] representing this [Class]
 */
public val Class<*>.asmType: Type get() = Type.getType(this)

/**
 * Yields a [Type] representing this [KClass]
 */
public val KClass<*>.asmType: Type get() = java.asmType

/**
 * Returns a [Type] representing [T]
 */
public inline fun <reified T> asmTypeOf(): Type = T::class.java.asmType

/**
 * Returns a [Type] representing [T], where [T] is primitive
 */
public inline fun <reified T : Any> primitiveTypeOf(): Type =
    (T::class.javaPrimitiveType
        ?: error("T is not a primitive type!")).asmType

/**
 * Returns a [Type] represented by the class [internalName]
 */
public fun asmTypeOf(internalName: String): Type = Type.getObjectType(internalName)

/**
 * Yields a [Type] represented by this [ClassNode]
 */
public val ClassNode.type: Type get() = Type.getObjectType(name)

/**
 * Returns all methods of this [ClassNode] as [MethodData]
 */
public val ClassNode.methodData: List<MethodData> get() = methods.map { MethodData(this, it) }

/**
 * Returns all fields of this [ClassNode] as [FieldData]
 */
public val ClassNode.fieldData: List<FieldData> get() = fields.map { FieldData(this, it) }

/**
 * Determines if a [Type] is primitive
 */
public val Type.isPrimitive: Boolean get() = sort !in listOf(Type.ARRAY, Type.OBJECT, Type.METHOD)

/**
 * Shorthand for loading classes with a given [ClassLoader]
 */
public fun ClassLoader.forName(name: String, load: Boolean = false): Class<*> = Class.forName(name, load, this)

/**
 * Returns the instruction that loads the stub value of a given [Type]
 * That is, it returns the default value the jvm would give a field with said [Type]
 */
public val Type.stubLoadInsn: Int
    get() = when (sort) {
        Type.VOID -> NOP
        Type.BOOLEAN, Type.BYTE, Type.SHORT, Type.CHAR, Type.INT -> ICONST_0
        Type.FLOAT -> FCONST_0
        Type.DOUBLE -> DCONST_0
        Type.LONG -> LCONST_0
        Type.OBJECT, Type.ARRAY -> ACONST_NULL
        else -> error("Invalid non-value type")
    }

/**
 * Finds the constructor of a [ClassNode]
 */
public val ClassNode.constructor: MethodNode? get() = methods.find { it.name == "<init>" }

/**
 * Finds the static initializer of a [ClassNode]
 */
public val ClassNode.staticInit: MethodNode? get() = methods.find { it.name == "<clinit>" }

/**
 * Gets the argument types of a [MethodNode]
 */
public val MethodNode.arguments: Array<Type> get() = Type.getArgumentTypes(desc)

/**
 * Gets the return type of a [MethodNode]
 */
public val MethodNode.returnType: Type get() = Type.getReturnType(desc)

/**
 * Gets the type of a [FieldNode]
 */
public val FieldNode.type: Type get() = Type.getType(desc)

public fun ClassNode.debug(): String {
    val bout = ByteArrayOutputStream()
    accept(TraceClassVisitor(null, PrintWriter(bout)))
    return bout.toByteArray().decodeToString()
}

public inline fun <reified T : AbstractInsnNode> AbstractInsnNode.nextOrNull(block: (T) -> Boolean): T? {
    var insn = next

    while (insn != null) {
        if (insn is T && block(insn)) return insn
        insn = insn.next
    }

    return null
}

public inline fun <reified T : AbstractInsnNode> AbstractInsnNode.next(block: (T) -> Boolean): T = nextOrNull<T>(block)
    ?: error("No matching insn was found!")

public inline fun <reified T : AbstractInsnNode> AbstractInsnNode.previousOrNull(block: (T) -> Boolean): T? {
    var insn = previous

    while (insn != null) {
        if (insn is T && block(insn)) return insn
        insn = insn.previous
    }

    return null
}

public inline fun <reified T : AbstractInsnNode> AbstractInsnNode.previous(block: (T) -> Boolean): T =
    previousOrNull<T>(block) ?: error("No matching insn was found!")

public val FieldInsnNode.isStatic: Boolean get() = opcode == GETSTATIC || opcode == PUTSTATIC

// Dirty way of doing it if you ask me
private fun List<Any>.asFrameValues() = listOf(first()) + drop(1).filterIndexed { idx, e ->
    val previous = this[idx]
    previous != LONG && previous != DOUBLE || e != TOP
}

/**
 * Adds the current stack state as a frame
 */
public fun AnalyzerAdapter.addCurrentFrame() {
    val frameLocals = locals.asFrameValues()
    val frameStack = stack.asFrameValues()
    visitFrame(F_NEW, frameLocals.size, frameLocals.toTypedArray(), frameStack.size, frameStack.toTypedArray())
}

/**
 * Converts a byte array (representing a classfile) to a [ClassNode]
 */
public fun ByteArray.asClassNode(options: Int = ClassReader.SKIP_DEBUG): ClassNode =
    ClassNode().also { ClassReader(this).accept(it, options) }

/**
 * Utility to create an instance of a given class
 * (useful when class extends an interface)
 * Assumes there is a no-arg constructor
 */
public inline fun <reified T> Class<*>.instance(): T = getConstructor().newInstance() as T