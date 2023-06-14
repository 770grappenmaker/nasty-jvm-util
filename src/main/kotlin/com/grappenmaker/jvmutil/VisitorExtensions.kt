package com.grappenmaker.jvmutil

import org.objectweb.asm.*
import org.objectweb.asm.Opcodes.*
import org.objectweb.asm.tree.ClassNode
import org.objectweb.asm.tree.FieldInsnNode
import org.objectweb.asm.tree.FieldNode
import java.lang.reflect.Constructor
import java.lang.reflect.Field
import java.lang.reflect.Method
import kotlin.reflect.KClass

/**
 * Invokes the described [method]
 */
public fun MethodVisitor.invokeMethod(method: MethodDescription): Unit = visitMethodInsn(
    method.invocationType.opcode,
    method.owner,
    method.name,
    method.descriptor,
    method.isInterface
)

/**
 * Invokes a method according to an [invocationType], [owner], [name] and [descriptor]
 */
public fun MethodVisitor.invokeMethod(
    invocationType: InvocationType,
    owner: String,
    name: String,
    descriptor: String
): Unit = visitMethodInsn(invocationType.opcode, owner, name, descriptor, invocationType == InvocationType.INTERFACE)

/**
 * Invokes a reflected method (java reflection)
 */
public fun MethodVisitor.invokeMethod(method: Method): Unit = invokeMethod(method.asDescription())

/**
 * Invokes a method according to [MethodData]
 */
public fun MethodVisitor.invokeMethod(data: MethodData): Unit = invokeMethod(data.asDescription())

///**
// * Invokes a method by kotlin reflect
// */
//fun MethodVisitor.invokeMethod(method: KFunction<*>) = invokeMethod(
//    method.javaMethod ?: error("No valid java method was found for ${method.name}!")
//)

private fun MethodVisitor.handleField(field: FieldDescription, opcode: Int) =
    visitFieldInsn(opcode, field.owner, field.name, field.descriptor)

/**
 * Loads a described [field] onto the stack
 */
public fun MethodVisitor.getField(field: FieldDescription): Unit =
    handleField(field, if (field.isStatic) GETSTATIC else GETFIELD)

/**
 * Loads a reflected [field] (java reflection) onto the stack
 */
public fun MethodVisitor.getField(field: Field): Unit = getField(field.asDescription())

///**
// * Loads a field (kotlin reflect) onto the stack
// */
//fun MethodVisitor.getField(prop: KProperty<*>) = getField(
//    prop.javaField ?: error("No valid java field was found for ${prop.name}!")
//)

/**
 * Loads a field by a field insn onto the stack
 */
public fun MethodVisitor.getField(insn: FieldInsnNode, static: Boolean = insn.isStatic): Unit =
    visitFieldInsn(if (static) GETSTATIC else GETFIELD, insn.owner, insn.name, insn.desc)

/**
 * Loads a field by a field node onto the stack
 */
public fun MethodVisitor.getField(owner: ClassNode, node: FieldNode): Unit =
    visitFieldInsn(if (node.isStatic) GETSTATIC else GETFIELD, owner.name, node.name, node.desc)

/**
 * Puts the stack top value to the field defined by the field node
 */
public fun MethodVisitor.setField(owner: ClassNode, node: FieldNode): Unit =
    visitFieldInsn(if (node.isStatic) PUTSTATIC else PUTFIELD, owner.name, node.name, node.desc)

/**
 * Puts the stack top value to the field defined by the field insn
 */
public fun MethodVisitor.setField(insn: FieldInsnNode, static: Boolean = insn.isStatic): Unit =
    visitFieldInsn(if (static) PUTSTATIC else PUTFIELD, insn.owner, insn.name, insn.desc)

///**
// * Loads the value of the getter of a property (kotlin reflect) onto the stack
// */
//fun MethodVisitor.getProperty(prop: KProperty<*>) = invokeMethod(prop.getter)

///**
// * Sets the value of a property given the value on the stack
// */
//fun MethodVisitor.setProperty(prop: KMutableProperty<*>) = invokeMethod(prop.setter)

/**
 * Loads [FieldData] onto the stack
 */
public fun MethodVisitor.getField(data: FieldData): Unit = getField(data.asDescription())

/**
 * Sets the current top stack value to a described [field]
 */
public fun MethodVisitor.setField(field: FieldDescription): Unit =
    handleField(field, if (field.isStatic) PUTSTATIC else PUTFIELD)

/**
 * Sets the current top stack value to a reflected [field] (java reflection)
 */
public fun MethodVisitor.setField(field: Field): Unit = setField(field.asDescription())

/**
 * Sets the current top stack value to [FieldData]
 */
public fun MethodVisitor.setField(data: FieldData): Unit = setField(data.asDescription())

/**
 * Constructs an object based on the given [constructor].
 * [block] is responsible for loading constructor parameters.
 */
public inline fun MethodVisitor.construct(constructor: MethodDescription, block: MethodVisitor.() -> Unit = {}) {
    require(constructor.isConstructor) { "constructor is not a constructor" }

    visitTypeInsn(NEW, constructor.owner)
    dup()
    block()
    visitMethodInsn(INVOKESPECIAL, constructor.owner, "<init>", constructor.descriptor, false)
}

/**
 * Constructs an object based on the given [constructor].
 * [block] is responsible for loading constructor parameters.
 */
public inline fun <T> MethodVisitor.construct(constructor: Constructor<T>, block: MethodVisitor.() -> Unit = {}): Unit =
    construct(constructor.asDescription(), block)

/**
 * Constructs an instance of [className] with a specified [descriptor] for the constructor call.
 * [block] is responsible for loading constructor parameters.
 */
public inline fun MethodVisitor.construct(className: String, descriptor: String, block: MethodVisitor.() -> Unit = {}) {
    visitTypeInsn(NEW, className)
    dup()
    block()
    visitMethodInsn(INVOKESPECIAL, className, "<init>", descriptor, false)
}

/**
 * Duplicates the top of the stack [n] times.
 */
public fun MethodVisitor.dup(n: Int = 1) {
    require(n >= 1) { "n < 1, cannot dup less than one time" }
    repeat(n) { visitInsn(DUP) }
}

/**
 * Pops the top of the stack [n] times.
 */
public fun MethodVisitor.pop(n: Int = 1) {
    require(n >= 1) { "n < 1, cannot pop less than one time" }
    repeat(n) { visitInsn(POP) }
}

/**
 * Returns the method based on a specific return opcode
 */
public fun MethodVisitor.returnMethod(opcode: Int = RETURN) {
    require(opcode in (IRETURN..RETURN)) { "Invalid return opcode" }
    visitInsn(opcode)
}

/**
 * Loads local variable with [index] onto stack using [opcode]
 */
public fun MethodVisitor.load(index: Int, opcode: Int = ALOAD) {
    require(opcode in (ILOAD..ALOAD)) { "Invalid load opcode" }
    visitVarInsn(opcode, index)
}

/**
 * Loads local variable with [index] onto for given [type]
 */
public fun MethodVisitor.load(index: Int, type: Type): Unit = load(index, type.getOpcode(ILOAD))

/**
 * Loads a `this` value onto the stack, or the first argument of a method when the method is static
 */
public fun MethodVisitor.loadThis(): Unit = visitVarInsn(ALOAD, 0)

/**
 * Stores the top stack value into local variable [index] using [opcode]
 */
public fun MethodVisitor.store(index: Int, opcode: Int = ASTORE) {
    require(opcode in (ISTORE..ASTORE)) { "Invalid store opcode" }
    visitVarInsn(opcode, index)
}

/**
 * Loads the singleton instance of a kotlin `object` ([kClass]) onto the stack
 */
public fun MethodVisitor.getObject(kClass: KClass<*>): Unit =
    getField(kClass.java.getField("INSTANCE"))

/**
 * Loads the companion of a kotlin type
 */
public fun MethodVisitor.getCompanion(of: KClass<*>): Unit = getField(of.java.getField("Companion"))

/**
 * Loads the companion of a kotlin type [T]
 */
public inline fun <reified T> MethodVisitor.getCompanion(): Unit = getCompanion(T::class)

/**
 * Loads the singleton instance of a kotlin `object` of [T] onto the stack
 */
public inline fun <reified T> MethodVisitor.getObject(): Unit = getObject(T::class)

/**
 * Describes the field for [System.out]
 */
private val stdoutField = FieldDescription(
    name = "out",
    descriptor = "Ljava/io/PrintStream;",
    owner = "java/lang/System",
    access = ACC_PUBLIC or ACC_STATIC or ACC_FINAL
)

/**
 * Describes the method for PrintStream#print
 */
public val printMethod: MethodDescription = MethodDescription(
    name = "print",
    descriptor = "(Ljava/lang/String;)V",
    owner = "java/io/PrintStream",
    access = ACC_PUBLIC
)

/**
 * Describes the method for PrintStream#println
 */
public val printlnMethod: MethodDescription = printMethod.copy(name = "${printMethod.name}ln")

/**
 * Loads [System.out] onto the stack
 */
public fun MethodVisitor.visitGetOut(): Unit = getField(stdoutField)

/**
 * Prints a value loaded by [block] to [System.out] with [println]
 */
public inline fun MethodVisitor.visitPrintln(block: MethodVisitor.() -> Unit) {
    visitGetOut()
    block()
    invokeMethod(printlnMethod)
}

/**
 * Prints a string to [System.out] with [println]
 */
public fun MethodVisitor.visitPrintln(string: String): Unit = visitPrintln { visitLdcInsn(string) }

/**
 * Prints a value loaded by [block] to [System.out] with [print]
 */
public inline fun MethodVisitor.visitPrint(block: MethodVisitor.() -> Unit) {
    visitGetOut()
    block()
    invokeMethod(printMethod)
}

/**
 * Prints a string to [System.out] with [print]
 */
public fun MethodVisitor.visitPrint(string: String): Unit = visitPrint { visitLdcInsn(string) }

/**
 * Prints the top stack value with [type] to [System.out]
 */
public fun MethodVisitor.printStackTop(type: Type = asmTypeOf<String>()) {
    require(type.sort != Type.ARRAY || type.elementType.sort == Type.CHAR) { "Cannot String#valueOf non char[]" }

    val isOrderTwoType = type.sort == Type.DOUBLE || type.sort == Type.LONG
    visitInsn(if (isOrderTwoType) DUP2 else DUP)

    val isObject = type.sort == Type.OBJECT
    val typeDesc = if (isObject) "Ljava/lang/Object;" else type.descriptor
    if (isObject) cast("java/lang/Object")

    invokeMethod(
        invocationType = InvocationType.STATIC,
        name = "valueOf",
        descriptor = "($typeDesc)Ljava/lang/String;",
        owner = "java/lang/String",
    )

    visitGetOut()
    visitInsn(SWAP)
    invokeMethod(printlnMethod)
}

/**
 * Loads a given constant [value] onto the stack
 */
public fun MethodVisitor.loadConstant(value: Any?): Unit = when (value) {
    null -> visitInsn(ACONST_NULL)
    true -> visitInsn(ICONST_1)
    false -> visitInsn(ICONST_0)
    is Byte -> {
        visitIntInsn(BIPUSH, value.toInt())
        visitInsn(I2B)
    }

    is Int -> when (value) {
        -1 -> visitInsn(ICONST_M1)
        0 -> visitInsn(ICONST_0)
        1 -> visitInsn(ICONST_1)
        2 -> visitInsn(ICONST_2)
        3 -> visitInsn(ICONST_3)
        4 -> visitInsn(ICONST_4)
        5 -> visitInsn(ICONST_5)
        in Byte.MIN_VALUE..Byte.MAX_VALUE -> visitIntInsn(BIPUSH, value)
        in Short.MIN_VALUE..Short.MAX_VALUE -> visitIntInsn(SIPUSH, value)
        else -> visitLdcInsn(value)
    }

    is Float -> when (value) {
        0f -> visitInsn(FCONST_0)
        1f -> visitInsn(FCONST_1)
        2f -> visitInsn(FCONST_2)
        else -> visitLdcInsn(value)
    }

    is Double -> when (value) {
        0.0 -> visitInsn(DCONST_0)
        1.0 -> visitInsn(DCONST_1)
        else -> visitLdcInsn(value)
    }

    is Long -> when (value) {
        0L -> visitInsn(LCONST_0)
        1L -> visitInsn(LCONST_1)
        in Byte.MIN_VALUE..Byte.MAX_VALUE -> {
            visitIntInsn(BIPUSH, value.toInt())
            visitInsn(I2L)
        }

        in Short.MIN_VALUE..Short.MAX_VALUE -> {
            visitIntInsn(SIPUSH, value.toInt())
            visitInsn(I2L)
        }

        else -> visitLdcInsn(value)
    }

    is Char -> {
        visitIntInsn(BIPUSH, value.code)
        visitInsn(I2C)
    }

    is Short -> {
        visitIntInsn(SIPUSH, value.toInt())
        visitInsn(I2S)
    }

    is String, is Type, is Handle, is ConstantDynamic -> visitLdcInsn(value)
    else -> error("Constant value ($value) is not a valid JVM constant!")
}

/**
 * Casts the top stack value to typeof [internalName]
 */
public fun MethodVisitor.cast(internalName: String): Unit = visitTypeInsn(CHECKCAST, internalName)

/**
 * Casts the top stack value a [Type]
 */
public fun MethodVisitor.cast(type: Type): Unit = visitTypeInsn(CHECKCAST, type.internalName)

/**
 * Casts the top stack value to the given [clazz]
 */
public fun MethodVisitor.cast(clazz: Class<*>): Unit = cast(clazz.internalName)

/**
 * Allows you to concatenate arbitrary values with [ConcatContext]
 */
public inline fun MethodVisitor.concat(block: ConcatContext.() -> Unit) {
    construct("java/lang/StringBuilder", "()V")
    ConcatContext(this).block()
    invokeMethod(
        invocationType = InvocationType.VIRTUAL,
        owner = "java/lang/StringBuilder",
        name = "toString",
        descriptor = "()Ljava/lang/String;"
    )
}

/**
 * DSL for concatenating strings
 * @see [MethodVisitor.concat]
 */
public class ConcatContext(private val parent: MethodVisitor) {
    private fun genericAppend(desc: String) = parent.invokeMethod(
        invocationType = InvocationType.VIRTUAL,
        owner = "java/lang/StringBuilder",
        name = "append",
        descriptor = "($desc)Ljava/lang/StringBuilder;"
    )

    private fun stringAppend() = genericAppend("Ljava/lang/String;")
    private fun appendByType(type: Type) = genericAppend(type.descriptor)
    private fun appendByType(clazz: Class<*>) = genericAppend(Type.getDescriptor(clazz))
    private fun <T : Any> appendByType(kClass: KClass<T>) =
        appendByType(kClass.javaPrimitiveType ?: kClass.java)

    /**
     * Appends a JVM constant to this [ConcatContext]
     */
    public fun appendConstant(value: Any?): Unit = with(parent) {
        if (value == null) {
            loadConstant("null")
            stringAppend()
            return
        }

        loadConstant(value)
        appendByType(value::class)
    }

    /**
     * Appends a JVM primitive, typed [type], loaded by [loader], to this [ConcatContext]
     */
    public fun appendPrimitive(type: Type, loader: MethodVisitor.() -> Unit) {
        require(type.sort != Type.OBJECT) { "Cannot append object as primitive, use appendObject" }
        require(type.sort != Type.ARRAY || type.elementType.sort == Type.CHAR) { "Cannot StringBuilder#append non char[]" }
        parent.loader()
        appendByType(type)
    }

    /**
     * Appends a constant string to this [ConcatContext]
     */
    public fun appendString(value: String): Unit = appendString { visitLdcInsn(value) }

    /**
     * Appends a string loaded by [loader] to this [ConcatContext]
     */
    public fun appendString(loader: MethodVisitor.() -> Unit) {
        parent.loader()
        stringAppend()
    }

    /**
     * Appends an object loaded by [loader] to this [ConcatContext]
     */
    public fun appendObject(loader: MethodVisitor.() -> Unit) {
        parent.loader()
        genericAppend("Ljava/lang/Object;")
    }
}

/**
 * Unboxes the top value of the operand stack to a given [type]
 */
public fun MethodVisitor.unbox(type: Type) {
    require(type.isPrimitive) { "type must be primitive!" }
    if (type.sort == Type.VOID) return // Not interesting at all (no instance of void exists anyway)

    // First, find the boxed type
    val boxedType = when (type.sort) {
        Type.CHAR -> "java/lang/Character"
        Type.BOOLEAN -> "java/lang/Boolean"
        else -> "java/lang/Number"
    }

    // Cast to boxed type
    cast(boxedType)

    // Find method to call on boxedType
    val (unboxMethod, unboxType) = when (type.sort) {
        Type.CHAR -> "charValue" to "C"
        Type.BOOLEAN -> "booleanValue" to "Z"
        Type.DOUBLE -> "doubleValue" to "D"
        Type.FLOAT -> "floatValue" to "F"
        Type.LONG -> "longValue" to "L"
        else -> "intValue" to "I"
    }

    // Unbox ahead!
    invokeMethod(InvocationType.VIRTUAL, boxedType, unboxMethod, "()$unboxType")
}

/**
 * Boxes the top value of the stack with primitive [type]
 */
public fun MethodVisitor.box(type: Type) {
    require(type.isPrimitive) { "type must be primitive!" }
    if (type.sort == Type.VOID) return // Not interesting at all (no instance of void exists anyway)

    val boxedType = type.boxedType
    invokeMethod(InvocationType.STATIC, boxedType, "valueOf", "(${type.descriptor})L$boxedType;")
}


/**
 * Loads a given [type] onto the stack as a Class<*>
 */
public fun MethodVisitor.loadTypeClass(type: Type): Unit = when (type.sort) {
    Type.METHOD -> error("Cannot load a method type as type class!")
    Type.OBJECT, Type.ARRAY -> loadConstant(type)
    else -> visitFieldInsn(GETSTATIC, type.boxedType, "TYPE", "Ljava/lang/Class;")
}

/**
 * Retrieves the classname of a primitive type's boxed class
 */
public val Type.boxedType: String
    get() = when (sort) {
        Type.CHAR -> "java/lang/Character"
        Type.BOOLEAN -> "java/lang/Boolean"
        Type.INT -> "java/lang/Integer"
        Type.LONG -> "java/lang/Long"
        Type.FLOAT -> "java/lang/Float"
        Type.DOUBLE -> "java/lang/Double"
        Type.BYTE -> "java/lang/Byte"
        Type.SHORT -> "java/lang/Short"
        Type.VOID -> "java/lang/Void"
        else -> error("type isn't primitive")
    }

public fun ClassVisitor.visitField(desc: FieldDescription): FieldVisitor =
    visitField(desc.access, desc.name, desc.descriptor, null, null)