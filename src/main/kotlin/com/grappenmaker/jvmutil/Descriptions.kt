package com.grappenmaker.jvmutil

import org.objectweb.asm.Opcodes.*
import org.objectweb.asm.Type
import org.objectweb.asm.tree.ClassNode
import org.objectweb.asm.tree.FieldNode
import org.objectweb.asm.tree.MethodNode
import java.lang.reflect.Constructor
import java.lang.reflect.Field
import java.lang.reflect.Method

/**
 * Wraps information about a method (invocation) into a datastructure
 */
public data class MethodDescription(
    val name: String,
    val descriptor: String,
    val owner: String,
    val access: Int,
    val isInterface: Boolean = false
)

public fun MethodDescription.isSimilar(desc: MethodDescription, matchOwner: Boolean = true): Boolean =
    name == desc.name && descriptor == desc.descriptor && (owner == desc.owner || !matchOwner)

public fun MethodDescription.isSimilar(data: MethodData, matchOwner: Boolean = true): Boolean =
    isSimilar(data.asDescription(), matchOwner)

/**
 * Whether the described method is `public`
 */
public val MethodDescription.isPublic: Boolean get() = access and ACC_PUBLIC != 0

/**
 * Whether the described method is `private`
 */
public val MethodDescription.isPrivate: Boolean get() = access and ACC_PRIVATE != 0

/**
 * Whether the described method is `protected`
 */
public val MethodDescription.isProtected: Boolean get() = access and ACC_PROTECTED != 0

/**
 * Whether the described method is `static`
 */
public val MethodDescription.isStatic: Boolean get() = access and ACC_STATIC != 0

/**
 * Whether the described method is `final`
 */
public val MethodDescription.isFinal: Boolean get() = access and ACC_FINAL != 0

/**
 * Whether the described method is a constructor
 */
public val MethodDescription.isConstructor: Boolean get() = name == "<init>"

/**
 * Wraps information about a field (reference) into a datastructure
 */
public data class FieldDescription(
    val name: String,
    val descriptor: String,
    val owner: String,
    val access: Int
)

/**
 * Whether the described field is `public`
 */
public val FieldDescription.isPublic: Boolean get() = access and ACC_PUBLIC != 0

/**
 * Whether the described field is `private`
 */
public val FieldDescription.isPrivate: Boolean get() = access and ACC_PRIVATE != 0

/**
 * Whether the described field is `static`
 */
public val FieldDescription.isStatic: Boolean get() = access and ACC_STATIC != 0

/**
 * Whether the described field is `final`
 */
public val FieldDescription.isFinal: Boolean get() = access and ACC_FINAL != 0

/**
 * Converts [MethodData] to a [MethodDescription]
 */
public fun MethodData.asDescription(): MethodDescription =
    MethodDescription(method.name, method.desc, owner.name, method.access, owner.isInterface)


/**
 * Converts a [MethodNode] to a [MethodDescription]
 */
public fun MethodNode.asDescription(owner: String, interfaceMethod: Boolean = false): MethodDescription =
    MethodDescription(name, desc, owner, access, interfaceMethod)

/**
 * Converts a [MethodNode] to a [MethodDescription]
 */
public fun MethodNode.asDescription(owner: ClassNode): MethodDescription = asDescription(owner.name, owner.isInterface)

/**
 * Converts a [Method] to a [MethodDescription]
 */
public fun Method.asDescription(): MethodDescription = MethodDescription(
    name = name,
    descriptor = Type.getMethodDescriptor(this),
    owner = declaringClass.internalName,
    access = modifiers,
    isInterface = declaringClass.isInterface
)

/**
 * Converts a [Constructor] to a [MethodDescription]
 */
public fun <T> Constructor<T>.asDescription(): MethodDescription = MethodDescription(
    name = "<init>",
    descriptor = Type.getConstructorDescriptor(this),
    owner = declaringClass.internalName,
    access = modifiers
)

/**
 * Converts [FieldData] to a [FieldDescription]
 */
public fun FieldData.asDescription(): FieldDescription =
    FieldDescription(field.name, field.desc, owner.name, field.access)

/**
 * Converts a [FieldNode] to a [FieldDescription]
 */
public fun FieldNode.asDescription(owner: String): FieldDescription =
    FieldDescription(name, desc, owner, access)

/**
 * Converts a [FieldNode] to a [FieldDescription]
 */
public fun FieldNode.asDescription(owner: ClassNode): FieldDescription = asDescription(owner.name)

/**
 * Converts a [Field] to a [FieldDescription]
 */
public fun Field.asDescription(): FieldDescription = FieldDescription(
    name = name,
    descriptor = Type.getDescriptor(type),
    owner = declaringClass.internalName,
    access = modifiers
)

/**
 * Describes how a method should be invoked. Is associated with an [opcode].
 */
public enum class InvocationType(public val opcode: Int) {
    SPECIAL(INVOKESPECIAL),
    DYNAMIC(INVOKEDYNAMIC),
    VIRTUAL(INVOKEVIRTUAL),
    INTERFACE(INVOKEINTERFACE),
    STATIC(INVOKESTATIC);

    public companion object {
        public fun getFromOpcode(opcode: Int): InvocationType {
            require(opcode in INVOKEVIRTUAL..INVOKEDYNAMIC) { "invoke opcode $opcode invalid!" }
            return enumValues<InvocationType>().single { it.opcode == opcode }
        }
    }
}

/**
 * Finds the way to invoke the described method
 */
public val MethodDescription.invocationType: InvocationType
    get() = when {
        isPrivate || name == "<init>" -> InvocationType.SPECIAL
        isStatic -> InvocationType.STATIC
        isInterface -> InvocationType.INTERFACE
        else -> InvocationType.VIRTUAL
    }

/**
 * Finds a way to invoke [MethodData]
 */
public val MethodData.invocationType: InvocationType
    get() = when {
        method.isPrivate || method.name == "<init>" -> InvocationType.SPECIAL
        method.isStatic -> InvocationType.STATIC
        owner.isInterface -> InvocationType.INTERFACE
        else -> InvocationType.VIRTUAL
    }