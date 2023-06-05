package com.grappenmaker.jvmutil

import org.objectweb.asm.MethodVisitor
import kotlin.reflect.KFunction
import kotlin.reflect.KMutableProperty
import kotlin.reflect.KProperty
import kotlin.reflect.jvm.javaMethod
import kotlin.reflect.jvm.javaField

private fun assertReflect() {
    runCatching { Class.forName("kotlin.reflect.full.KClasses") }.onFailure {
        throw IllegalStateException(
            "Impossible to reference kotlin-reflect, make sure you depend on the correct feature!",
            it
        )
    }
}

/**
 * Invokes a method by kotlin reflect
 */
public fun MethodVisitor.invokeMethod(method: KFunction<*>) {
    assertReflect()
    invokeMethod(
        method.javaMethod ?: error("No valid java method was found for ${method.name}!")
    )
}

/**
 * Loads a field (kotlin reflect) onto the stack
 */
public fun MethodVisitor.getField(prop: KProperty<*>) {
    assertReflect()
    getField(
        prop.javaField ?: error("No valid java field was found for ${prop.name}!")
    )
}

/**
 * Loads the value of the getter of a property (kotlin reflect) onto the stack
 */
public fun MethodVisitor.getProperty(prop: KProperty<*>) {
    assertReflect()
    invokeMethod(prop.getter)
}

/**
 * Sets the value of a property given the value on the stack
 */
public fun MethodVisitor.setProperty(prop: KMutableProperty<*>) {
    assertReflect()
    invokeMethod(prop.setter)
}
