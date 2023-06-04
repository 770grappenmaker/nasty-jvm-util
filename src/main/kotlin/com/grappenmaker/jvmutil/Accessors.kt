package com.grappenmaker.jvmutil

import org.objectweb.asm.ClassVisitor
import org.objectweb.asm.Label
import org.objectweb.asm.MethodVisitor
import org.objectweb.asm.Opcodes.*
import org.objectweb.asm.Type
import org.objectweb.asm.tree.LdcInsnNode
import org.objectweb.asm.tree.TypeInsnNode
import java.lang.reflect.AccessibleObject
import java.lang.reflect.Field
import java.lang.reflect.Method

private const val accessorPackagePrefix = "com/grappenmaker/jvmutil/accessor/generated"

private var accessorCounter = 0
    get() = field++

//private val defaultLoader: (bytes: ByteArray, name: String) -> Class<*> get() {
//    val dummy = object {}
//    val cl = object : ClassLoader(dummy.javaClass.classLoader) {
//        fun create(bytes: ByteArray, name: String) =
//            defineClass(name, bytes, 0, bytes.size)
//    }
//
//    return cl::create
//}

public class AccessorRegistry(parent: ClassLoader) : ClassLoader(parent) {
    public val accessors: MutableList<AccessorData<*, *>> = mutableListOf()

    public fun loadAccessor(bytes: ByteArray, name: String): Class<*> = defineClass(name, bytes, 0, bytes.size)

    /**
     * Finds [AccessorData] by an interface type's internal [name]
     */
    public fun findAccessor(name: String): AccessorData<*, *>? = accessors.find { it.virtualType.internalName == name }

    public fun getAppClass(name: String): Class<*> = loadClass(name.replace('/', '.'))
}

public data class AccessorData<V : Any, S : StaticAccessor<V>>(
    val internalName: ElementFinder<String>,
    val methods: Map<String, ElementFinder<MethodData>>,
    val fields: Map<String, ElementFinder<FieldData>>,
    val virtualType: Class<V>,
    val staticType: Class<S>,
    private val virtualGenerator: (AccessorData<V, S>) -> Class<*>,
    private val staticGenerator: (AccessorData<V, S>) -> S,
    val allowNotImplemented: Boolean = false,
) {
    public val virtualAccessorName: String = "${virtualType.simpleName}Virtual$accessorCounter"
    public val staticAccessorName: String = "${virtualType.simpleName}Static$accessorCounter"
    public val fullVirtualAccessorName: String get() = "$accessorPackagePrefix/$virtualAccessorName"
    public val fullStaticAccessorName: String get() = "$accessorPackagePrefix/$staticAccessorName"
    public val virtualAccessor: Class<*> by lazy { virtualGenerator(this) }
    public val staticAccessor: S by lazy { staticGenerator(this) }

    public val delegateFieldName: String = "\$\$delegate"
    public val delegateFieldType: String get() = internalName()
    public val delegateFieldDesc: String get() = "L$delegateFieldType;"
}

private fun AccessorData<*, *>.loadDelegate(mv: MethodVisitor) {
    val label = Label()
    mv.dup()
    mv.visitJumpInsn(IFNULL, label)

    mv.cast(fullVirtualAccessorName)
    mv.visitMethodInsn(INVOKEVIRTUAL, fullVirtualAccessorName, "getDelegate", "()Ljava/lang/Object;", false)

    mv.visitLabel(label)
}

public sealed interface AccessorImplementation {
    public val receiverMethod: Method
}

public data class FieldImplementation(
    override val receiverMethod: Method,
    val target: FieldData,
    val type: Type = if (receiverMethod.name.startsWith("set")) Type.SETTER else Type.GETTER
) : AccessorImplementation {
    public enum class Type(public val paramCount: Int, public val namePrefix: String) {
        GETTER(0, "get"), SETTER(1, "set");

        public fun match(params: Int, name: String): Boolean = paramCount == params && name.startsWith(namePrefix)
    }
}

public data class MethodImplementation(
    override val receiverMethod: Method,
    val target: MethodData
) : AccessorImplementation

public data class NotImplementedImplementation(override val receiverMethod: Method) : AccessorImplementation

public fun defaultSelectImplementation(
    methods: Map<String, ElementFinder<MethodData>>,
    fields: Map<String, ElementFinder<FieldData>>,
    receiverMethod: Method,
    allowNotImplemented: Boolean,
): AccessorImplementation? {
    val name = receiverMethod.name

    // Check if this is a getter/setter and therefore could be a field
    val paramCount = receiverMethod.parameterCount
    val isGetter = FieldImplementation.Type.GETTER.match(paramCount, name)
    val isSetter = FieldImplementation.Type.SETTER.match(paramCount, name)
    val field = if ((isGetter || isSetter) && name.length > 3) {
        val fieldName = name.drop(3)
        (fields[fieldName]?.nullable()
            ?: fields[fieldName.replaceFirstChar { it.lowercase() }]?.nullable())
            ?.let { FieldImplementation(receiverMethod, it) }
    } else fields[name]?.nullable()?.let { FieldImplementation(receiverMethod, it) }

    // If not a field, or no field was found, it could be a method
    return field
        ?: methods[name]?.nullable()?.let { MethodImplementation(receiverMethod, it) }
        ?: if (allowNotImplemented) NotImplementedImplementation(receiverMethod) else null
}

/**
 * Internal utility for generating an accessor
 */
// name here is the internal name of the generating class
public fun internalGenerateAccessor(
    registry: AccessorRegistry,
    methods: Map<String, ElementFinder<MethodData>>,
    fields: Map<String, ElementFinder<FieldData>>,
    accessorName: String,
    typeToImplement: Class<*>,
    constructorImpl: ClassVisitor.(name: String, reflectedMethods: Set<ReflectedElementData>) -> Unit,
    beforeGenerate: ClassVisitor.(name: String) -> Unit = {},
    receiverLoader: MethodVisitor.(name: String, isReflected: Boolean) -> Unit,
    implementationSelector: (
        methods: Map<String, ElementFinder<MethodData>>,
        fields: Map<String, ElementFinder<FieldData>>,
        targetMethod: Method,
        allowNotImplemented: Boolean
    ) -> AccessorImplementation? = ::defaultSelectImplementation,
    allowNotImplemented: Boolean,
    fullReflect: Boolean = false,
): Class<*> {
    require(typeToImplement.isInterface) { "$typeToImplement is not an interface to implement!" }
    val fullAccessorName = "$accessorPackagePrefix/$accessorName"

    // Start generating class
    return generateClass(
        name = accessorName,
        implements = listOf(typeToImplement.internalName),
        defaultConstructor = false,
        loader = registry::loadAccessor
    ) {
        // Check if we need to implement StaticAccessor
        val isStaticAccessor = StaticAccessor::class.java in typeToImplement.interfaces
        val staticAccessorIllegal = listOf("isInstance", "cast")

        // Find how to implement the methods requested
        val implementations = typeToImplement.declaredMethods
            .filter { !it.isDefault && (!isStaticAccessor || it.name !in staticAccessorIllegal) }
            .map { receiverMethod ->
                implementationSelector(methods, fields, receiverMethod, allowNotImplemented) ?: error(
                    """
                    |No implementation was found for $receiverMethod (not implemented was not allowed)
                    |
                    |Methods in finder:
                    |${methods.toList().joinToString { (name, m) -> "${name}: ${m.nullable()}" }}
                    |
                    |Fields in finder:
                    |${fields.toList().joinToString { (name, f) -> "${name}: ${f.nullable()}" }}
                """.trimMargin()
                )
            }

        // Before generating class hook
        beforeGenerate(fullAccessorName)

        // Keep track of all method/field access that has to be reflected
        val reflectedElements = hashSetOf<ReflectedElementData>()

        fun implementField(impl: FieldImplementation) {
            val (receiverMethod, target, type) = impl
            val (targetOwner, targetField) = target
            val (name, desc) = receiverMethod.asDescription()
            val desiredType = Type.getReturnType(desc)

            // If the field is private, generate a special field
            // to store the reflected Field in
            val isReflected = !targetField.isPublic
            val rfd = ReflectedFieldData(target)
            if (isReflected && reflectedElements.add(rfd)) implementRED(rfd)

            // Start generating the method
            generateMethod(name, desc) {
                val isSetter = type == FieldImplementation.Type.SETTER

                if (isReflected) {
                    // If the field is reflected, load the correct field
                    loadThis()
                    getRED(rfd, fullAccessorName)
                }

                // Load receiver on stack
                receiverLoader(fullAccessorName, isReflected)

                if (isSetter) {
                    // When field is setter, load new value for the field
                    // Type of the setter param
                    val setterType = Type.getArgumentTypes(desc).first()

                    // Type of the target field
                    val fieldType = Type.getType(targetField.desc)

                    // Load setter param
                    loadAccessorParams(
                        registry,
                        arrayOf(setterType),
                        arrayOf(fieldType),
                        isReflected,
                        isArgumentsArray = false
                    )
                }

                if (isReflected) {
                    // When field is reflected, apply with reflection
                    val mDesc =
                        if (isSetter) "(Ljava/lang/Object;Ljava/lang/Object;)V"
                        else "(Ljava/lang/Object;)Ljava/lang/Object;"

                    invokeMethod(
                        InvocationType.VIRTUAL,
                        owner = "java/lang/reflect/Field",
                        name = if (isSetter) "set" else "get",
                        descriptor = mDesc
                    )

                    // Lastly, unbox primitives and cast when necessary
                    if (!isSetter && desiredType.isPrimitive) {
                        // Unbox when primitive (primitive != Object, which is the return type of get)
                        unbox(desiredType)
                    }
                } else {
                    // Normal retrieval
                    val fieldDesc = targetField.asDescription(targetOwner)
                    if (isSetter) setField(fieldDesc) else getField(fieldDesc)
                }

                // Return&cast result
                if (isSetter) returnMethod() else returnAccessedValue(registry, desiredType)
            }
        }

        fun implementMethod(impl: MethodImplementation) {
            val (receiverMethod, target) = impl
            val (targetOwner, targetMethod) = target
            val (name, desc) = receiverMethod.asDescription()
            val arguments = Type.getArgumentTypes(desc)
            val targetArguments = targetMethod.arguments

            require(arguments.size == targetArguments.size) {
                "Argument count mismatch for $receiverMethod"
            }

            // Check if the method is a constructor
            val isCtor = targetMethod.isConstructor

            // If the method is private, generate a special field
            // to store the reflected method in
            val isReflected = !targetMethod.isPublic || fullReflect
            val rmd = ReflectedMethodData(target)
            if (isReflected) {
                reflectedElements += rmd
                implementRED(rmd)
            }

            // If the method is an "abstract ctor", do not allow
            require(!targetOwner.isAbstract || !isCtor) {
                val t = "${targetMethod.name}${targetMethod.desc} in ${targetOwner.name}"
                "Constructor accessors cannot be in an abstract class. Targeted method: $t"
            }

            // Start actually generating the method
            generateMethod(name, desc) {
                if (isReflected) {
                    // If the method is reflected, load the correct field
                    loadThis()
                    getRED(rmd, fullAccessorName)
                }

                if (isCtor) {
                    if (!isReflected) {
                        // If the method is a constructor, push a new instance
                        visitTypeInsn(NEW, targetOwner.name)
                        dup()
                    }
                } else {
                    // Load receiver on stack
                    receiverLoader(fullAccessorName, isReflected)
                }

                // Load all parameters on stack
                loadAccessorParams(registry, arguments, targetArguments, isReflected)

                // Call the target method
                val returnType = Type.getReturnType(desc)
                if (isReflected) {
                    // When method is reflected, invoke with reflection
                    invokeMethod(
                        InvocationType.VIRTUAL,
                        rmd.fieldType,
                        if (isCtor) "newInstance" else "invoke",
                        "(${if (!isCtor) "Ljava/lang/Object;" else ""}[Ljava/lang/Object;)Ljava/lang/Object;"
                    )

                    // Lastly, unbox primitives and cast when necessary
                    if (returnType.isPrimitive) {
                        // Unbox when primitive (primitive != Object, which is the return type of invoke)
                        unbox(returnType)
                    }
                } else {
                    // Normal invocation
                    invokeMethod(targetMethod.asDescription(targetOwner))
                }

                // Return&cast result
                returnAccessedValue(registry, returnType)
            }
        }

        implementations.forEach { impl ->
            when (impl) {
                is FieldImplementation -> implementField(impl)
                is MethodImplementation -> implementMethod(impl)
                is NotImplementedImplementation -> {
                    val (name, desc) = impl.receiverMethod.asDescription()
                    generateMethod(name, desc) {
                        construct("java/lang/UnsupportedOperationException", "(Ljava/lang/String;)V") {
                            loadConstant("$name $desc was not found during class finding, method not implemented")
                        }

                        visitInsn(ATHROW)
                    }
                }
            }
        }

        constructorImpl(fullAccessorName, reflectedElements)
    }
}

/**
 * Generates a non-static accessor type for [data]
 */
public fun AccessorRegistry.generateAccessor(data: AccessorData<*, *>): Class<*> {
    val foundClass = data.internalName.nullable()
        ?: error("finder hasn't found a matching class for ${data.virtualType} yet!")

    // Return newly generated class
    return internalGenerateAccessor(
        methods = data.methods,
        fields = data.fields,
        accessorName = data.virtualAccessorName,
        typeToImplement = data.virtualType,
        beforeGenerate = { name ->
            // Generate delegate field
            visitField(ACC_PUBLIC or ACC_FINAL, data.delegateFieldName, data.delegateFieldDesc, null, null)

            // Generate getDelegate method
            generateMethod("getDelegate", "()Ljava/lang/Object;") {
                loadThis()
                visitFieldInsn(GETFIELD, name, data.delegateFieldName, data.delegateFieldDesc)
                returnMethod(ARETURN)
            }

            // Override equals, hashCode, toString
            generateMethod("equals", "(Ljava/lang/Object;)Z") {
                load(1)
                visitTypeInsn(INSTANCEOF, name)

                val label = Label()
                visitJumpInsn(IFEQ, label)

                loadThis()
                visitFieldInsn(GETFIELD, name, data.delegateFieldName, data.delegateFieldDesc)
                load(1)
                data.loadDelegate(this)
                visitMethodInsn(INVOKEVIRTUAL, "java/lang/Object", "equals", "(Ljava/lang/Object;)Z", false)
//                invokeMethod(Object::equals)
                returnMethod(IRETURN)

                visitLabel(label)
                loadConstant(false)
                returnMethod(IRETURN)
            }

            generateMethod("hashCode", "()I") {
                loadThis()
                visitFieldInsn(GETFIELD, name, data.delegateFieldName, data.delegateFieldDesc)
                visitMethodInsn(INVOKEVIRTUAL, "java/lang/Object", "hashCode", "()I", false)
//                invokeMethod(Object::hashCode)
                returnMethod(IRETURN)
            }

            generateMethod("toString", "()Ljava/lang/String;") {
                loadThis()
                visitFieldInsn(GETFIELD, name, data.delegateFieldName, data.delegateFieldDesc)
                visitMethodInsn(INVOKEVIRTUAL, "java/lang/Object", "toString", "()Ljava/lang/String;", false)
//                invokeMethod(Object::toString)
                returnMethod(ARETURN)
            }
        },
        receiverLoader = { name, _ ->
            loadThis()
            visitFieldInsn(GETFIELD, name, data.delegateFieldName, data.delegateFieldDesc)
        },
        constructorImpl = { name, reflectedElements ->
            // Create constructor
            generateMethod("<init>", "(Ljava/lang/Object;)V") {
                val binaryFoundName = foundClass.replace('/', '.')

                // Call super() on Object
                loadThis()
                visitMethodInsn(INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false)

                // Initialize delegate field
                loadThis()
                load(1)

//                if (data.fullReflect) {
//                    loadConstant(binaryFoundName)
//                    invokeMethod(::getAppClass)
//                    visitInsn(SWAP)
//                    invokeMethod(Class<*>::cast)
//                } else cast(foundClass)

                cast(foundClass)

                visitFieldInsn(PUTFIELD, name, data.delegateFieldName, data.delegateFieldDesc)

                // Initialize reflected methods
                implInitREDs(
                    reflectedElements,
                    foundClass = binaryFoundName,
                    fullAccessorName = name
                )

                // End constructor
                returnMethod()
            }
        },
        allowNotImplemented = data.allowNotImplemented,
        registry = this
    )
}

/**
 * Generates a static accessor type for [data]
 */
public inline fun <reified T : StaticAccessor<*>> AccessorRegistry.generateStaticAccessor(data: AccessorData<*, *>): T {
    val foundClass = data.internalName.nullable()
        ?: error("finder hasn't found a matching class for ${data.virtualType} yet!")

    val virtualAccessor = data.virtualAccessor

    return internalGenerateAccessor(
        methods = data.methods,
        fields = data.fields,
        accessorName = data.staticAccessorName,
        typeToImplement = data.staticType,
        beforeGenerate = {
            // Implement cast and isInstance
            generateMethod("cast", "(Ljava/lang/Object;)Ljava/lang/Object;") {
                construct(virtualAccessor.internalName, "(Ljava/lang/Object;)V") {
                    // Load receiver/first constructor param
                    load(1)
                }

                returnMethod(ARETURN)
            }

            generateMethod("isInstance", "(Ljava/lang/Object;)Z") {
                // Load receiver
                load(1)

                // Check with INSTANCEOF
                visitTypeInsn(INSTANCEOF, foundClass)

                returnMethod(IRETURN)
            }
        },
        receiverLoader = { _, isReflected -> if (isReflected) loadConstant(null) },
        constructorImpl = { name, reflectedMethods ->
            // Create constructor
            generateMethod("<init>", "()V") {
                // Call super() on Object
                loadThis()
                visitMethodInsn(INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false)

                // Initialize reflected methods
                implInitREDs(
                    reflectedElements = reflectedMethods,
                    foundClass = foundClass.replace('/', '.'),
                    fullAccessorName = name
                )

                // End constructor
                returnMethod()
            }
        },
        allowNotImplemented = data.allowNotImplemented,
        registry = this
    ).instance()
}

/**
 * Internal utility for loading all params given by [arguments] and [targetArguments] to the stack
 * Will also wrap into Object[] when [isReflected] is true
 */
private fun MethodVisitor.loadAccessorParams(
    registry: AccessorRegistry,
    arguments: Array<Type>,
    targetArguments: Array<Type>,
    isReflected: Boolean = false,
    isArgumentsArray: Boolean = true,
) {
    // Make sure to create an array for the arguments when reflected
    if (isReflected && isArgumentsArray) {
        loadConstant(arguments.size)
        visitTypeInsn(ANEWARRAY, "java/lang/Object")
    }

    // Load all parameters on stack
    var paramIndex = 1 // 1 because 0=this
    for ((idx, paramType) in arguments.withIndex()) {
        // When reflected, push the element index and dup args array
        if (isReflected && isArgumentsArray) {
            dup()
            loadConstant(idx)
        }

        // Load the actual parameter on stack
        load(paramIndex, paramType)

        // Check if we need to unwrap an accessor
        val accessorType = registry.findAccessor(paramType.internalName)
        accessorType?.loadDelegate(this)

        // When reflected, push the parameter to the array
        when {
            isReflected -> {
                // Box primitives (since Object[] cannot contain primitives)
                if (paramType.isPrimitive) box(paramType)
                if (isArgumentsArray) visitInsn(AASTORE)
            }

            else -> {
                // Find the correct target type of this parameter
                val targetType = targetArguments[idx].internalName

                // Cast when not matching type (could fail, Prayge)
                if (!paramType.isPrimitive && paramType.internalName != targetType) cast(targetType)
            }
        }

        // Increment param index, keeping doubles and longs in mind
        paramIndex += paramType.size
    }
}

private fun MethodVisitor.getRegistry() {
    loadThis()
    visitMethodInsn(INVOKEVIRTUAL, "java/lang/Object", "getClass", "()Ljava/lang/Class;", false)
    visitMethodInsn(INVOKEVIRTUAL, "java/lang/Class", "getClassLoader", "()Ljava/lang/ClassLoader;", false)
    cast(AccessorRegistry::class.java)
}

private fun MethodVisitor.wrapAccessor(accessorData: AccessorData<*, *>) {
    val vAccessorName = "$accessorPackagePrefix/${accessorData.virtualAccessorName}"

    // Ensure loaded when called
    getRegistry()
    loadConstant(accessorData.virtualType.internalName)
    invokeMethod(AccessorRegistry::class.java.getMethod("findAccessor", String::class.java))
    invokeMethod(AccessorData::class.java.getMethod("getVirtualAccessor"))
//    invokeMethod(AccessorRegistry::findAccessor)
//    getProperty(AccessorData<*, *>::virtualAccessor)
    pop()

    // on stack: obj
    visitTypeInsn(NEW, vAccessorName)
    // on stack: obj, uninit vAccessor
    visitInsn(DUP_X1)
    // on stack: uninit vAccessor, obj, uninit vAccessor
    visitInsn(SWAP)
    // on stack: 2x uninit vAccessor, obj
    visitMethodInsn(INVOKESPECIAL, vAccessorName, "<init>", "(Ljava/lang/Object;)V", false)
    // on stack: vAccessor (initialized)
}

private fun MethodVisitor.returnAccessedValue(registry: AccessorRegistry, returnType: Type) {
    // Let's see if we need to return an accessor
    val returnedAccessor = registry.findAccessor(returnType.internalName)
    if (returnedAccessor != null) {
        // Two paths: if returned value is null, do not make an accessor
        val label = Label()
        dup()
        visitJumpInsn(IFNONNULL, label)
        returnMethod(returnType.getOpcode(IRETURN))

        visitLabel(label)
        wrapAccessor(returnedAccessor)
    }

    // To make sure it works out, perform checked cast
    if (!returnType.isPrimitive) cast(returnType.internalName)

    // Return result
    returnMethod(returnType.getOpcode(IRETURN))
}

/**
 * Internal utility for initializing all reflected method/field access
 * [foundClass] is the binary name of the class that we are delegating to
 * [fullAccessorName] is required in order to get the field successfully
 */
public fun MethodVisitor.implInitREDs(
    reflectedElements: Set<ReflectedElementData>,
    foundClass: String,
    fullAccessorName: String
) {
    if (reflectedElements.isNotEmpty()) {
        // Find class to get all the methods from
        getRegistry()
        loadConstant(foundClass)
        invokeMethod(AccessorRegistry::class.java.getMethod("getAppClass", String::class.java))
//        invokeMethod(AccessorRegistry::getAppClass)

        for (red in reflectedElements) {
            // Dup class so it can be reused
            dup()

            // Push this
            loadThis()

            // Swap top stack values out (stack is now class - this - class)
            visitInsn(SWAP)

            // Get the values of the fields
            when (red) {
                is ReflectedFieldData -> {
                    val targetField = red.target.field
                    loadConstant(targetField.name)

                    // Get Field
                    invokeMethod(Class::class.java.getMethod("getDeclaredField"))
//                    invokeMethod(Class<*>::getDeclaredField)

                    // Make Field accessible
                    dup()
                    loadConstant(true)
                    invokeMethod(Field::class.java.getMethod("setAccessible", Boolean::class.javaPrimitiveType))
                }

                is ReflectedMethodData -> {
                    val targetMethod = red.target.method
                    if (!targetMethod.isConstructor) {
                        // Load method name
                        loadConstant(targetMethod.name)
                    }

                    // Load param types
                    val params = Type.getArgumentTypes(targetMethod.desc)
                    loadConstant(params.size)
                    visitTypeInsn(ANEWARRAY, "java/lang/Class")

                    params.forEachIndexed { idx, param ->
                        dup()
                        loadConstant(idx)
                        if (param.sort == Type.OBJECT) {
                            getRegistry()
                            loadConstant(param.className)
                            invokeMethod(AccessorRegistry::class.java.getMethod("getAppClass", String::class.java))
//                            invokeMethod(::getAppClass)
                        } else loadTypeClass(param)
                        visitInsn(AASTORE)
                    }

                    // Get method
                    invokeMethod(
                        if (targetMethod.isConstructor) Class::class.java.getMethod("getDeclaredConstructor")
                        else Class::class.java.getMethod("getDeclaredMethod")
                    )

                    // Make method accessible
                    dup()
                    loadConstant(true)
                    invokeMethod(
                        AccessibleObject::class.java.getMethod(
                            "setAccessible",
                            Boolean::class.javaPrimitiveType
                        )
                    )
                }
            }

            // Set the accessor field
            setRED(red, fullAccessorName)
        }

        // App class still on stack, pop
        pop()
    }
}

public sealed class ReflectedElementData(public val fieldName: String, public val fieldType: String) {
    public val fieldDesc: String get() = "L$fieldType;"
}

private fun ClassVisitor.implementRED(red: ReflectedElementData) =
    visitField(ACC_PRIVATE or ACC_FINAL, red.fieldName, red.fieldDesc, null, null)

private fun MethodVisitor.getRED(red: ReflectedElementData, owner: String) =
    visitFieldInsn(GETFIELD, owner, red.fieldName, red.fieldDesc)

private fun MethodVisitor.setRED(red: ReflectedElementData, owner: String) =
    visitFieldInsn(PUTFIELD, owner, red.fieldName, red.fieldDesc)

/**
 * A container for holding all method access that needs to be reflected
 */
public data class ReflectedMethodData(val target: MethodData) : ReflectedElementData(
    fieldName = "reflectedMethod_${target.method.name}\$${escapeDesc(target.method.desc)}",
    fieldType = if (target.method.isConstructor) "java/lang/reflect/Constructor" else "java/lang/reflect/Method"
)

/**
 * A container for holding all field access that needs to be reflected
 */
public data class ReflectedFieldData(val target: FieldData) : ReflectedElementData(
    fieldName = "reflectedField_${target.field.name}\$${escapeDesc(target.field.desc)}",
    fieldType = "java/lang/reflect/Field"
)

private fun escapeDesc(desc: String) = desc
    .replace(".", "PD\$") // PerioD
    .replace(";", "SC\$") // SemiColon
    .replace("[", "AR\$") // ARray
    .replace("/", "SL\$") // SLash

/**
 * Type used for representing accessor pairs
 * Useful for creating instances and such
 * [static] can be delegated to with a companion object for ease of access
 */
public data class GeneratedAccessor<V : Any, S : StaticAccessor<V>>(val virtual: Class<*>, val static: S)

/**
 * Creates accessor by a [ClassFinder]
 */
public inline fun <reified V : Any, reified S : StaticAccessor<V>> AccessorRegistry.accessor(
    finder: ClassFinder,
    allowNotImplemented: Boolean = false,
    crossinline preinit: () -> Unit = {}
): Lazy<GeneratedAccessor<V, S>> = accessor<V, S>(
    finder = finder.map { it.name },
    methods = finder.methods.contents,
    fields = finder.fields.contents,
    allowNotImplemented,
    preinit
)

/**
 * Implements accessors for type [V], with a static accessor represented by type [S]
 */
public inline fun <reified V : Any, reified S : StaticAccessor<V>> AccessorRegistry.accessor(
    finder: ElementFinder<String>,
    methods: Map<String, ElementFinder<MethodData>>,
    fields: Map<String, ElementFinder<FieldData>>,
    allowNotImplemented: Boolean = false,
    crossinline preinit: () -> Unit = {}
): Lazy<GeneratedAccessor<V, S>> {
    // Early interface check
    val virtualType = V::class.java
    val staticType = S::class.java
    require(virtualType.isInterface) { "Type V is not an interface type" }
    require(staticType.isInterface) { "Type S is not an interface type" }

    val ad = AccessorData(
        finder, methods, fields,
        virtualType,
        staticType,
        virtualGenerator = {
            runCatching { generateAccessor(it) }
                .onFailure {
                    println("Error generating virtual accessor:")
                    it.printStackTrace()
                }.getOrThrow()
        },
        staticGenerator = {
            runCatching { generateStaticAccessor<S>(it) }.onFailure {
                println("Error generating static accessor:")
                it.printStackTrace()
            }.getOrThrow()
        },
        allowNotImplemented = allowNotImplemented
//        fullReflect = true
    )

    accessors += ad
    return lazy {
        runCatching {
            preinit()
            GeneratedAccessor(ad.virtualAccessor, ad.staticAccessor)
        }.onFailure {
            println("Failed initializing accessor ${ad.virtualAccessorName}")
            it.printStackTrace()
        }.getOrThrow()
    }
}

/**
 * Interface that a static accessor will implement (and therefore accessors must extend)
 * [T] is the type of the non-static accessor
 */
public interface StaticAccessor<T : Any> {
    /**
     * Checks if [obj] is an instance of the type this is being called on
     */
    public fun isInstance(obj: Any): Boolean

    /**
     * Casts [obj] to a delegate of this type
     */
    public fun cast(obj: Any): T
}

/**
 * Utility for accessors to "smart cast" certain objects
 * That is, an accessor static type checks if the [obj] can safely be wrapped into its virtual accessor
 * The [block] will only execute if this cast succeeds
 */
public inline fun <V : Any> StaticAccessor<V>.smartCast(obj: Any, block: (V) -> Unit) {
    if (isInstance(obj)) block(cast(obj))
}

/**
 * Utility to do a [StaticAccessor.cast], but it accepts [InstanceAccessor]s
 */
public fun <T : Any> StaticAccessor<T>.castAccessor(obj: InstanceAccessor): T = cast(obj.delegate)

/**
 * Utility to do a [StaticAccessor.isInstance] check, but it accepts [InstanceAccessor]s
 */
public fun <T : Any> StaticAccessor<T>.accessorIsInstance(obj: InstanceAccessor): Boolean = isInstance(obj.delegate)

/**
 * Utility for accessors to "smart cast" [InstanceAccessor]s
 */
public inline fun <V : Any> StaticAccessor<V>.smartCast(obj: InstanceAccessor, block: (V) -> Unit): Unit =
    smartCast(obj.delegate, block)

/**
 * Optional interface that an instance accessor is allowed to implement
 * This allows you to get the receiver/delegate of an instance of the accessor
 */
public interface InstanceAccessor {
    public val delegate: Any
}

// Utility for handling empty types (extra type safety)
public inline fun <reified V : Any, reified S : StaticAccessor<V>> AccessorRegistry.emptyAccessor(
    noinline type: () -> String
): Lazy<GeneratedAccessor<V, S>> = accessor<V, S>(LazyElementFinder(type), emptyMap(), emptyMap())

// Utility for handling enums
public inline fun <reified V : Any, reified S : StaticAccessor<V>> AccessorRegistry.enumAccessor(
    finders: FinderContext,
    crossinline preinit: () -> Unit = {},
    crossinline extra: ClassContext.() -> Unit = {}
): Lazy<GeneratedAccessor<V, S>> = accessor<V, S>(finders.enumFinder<S>(extra), false, preinit)

public inline fun <reified S : StaticAccessor<*>> FinderContext.enumFinder(
    crossinline extra: ClassContext.() -> Unit = {}
): ClassFinder = findClass {
    node extends internalNameOf<Enum<*>>()

    methods {
        named("values") { method.isStatic() }
        named("valueOf") { method.isStatic() }
    }

    fields {
        S::class.java.declaredMethods.filter { it.name.startsWith("get") }.forEach { m ->
            val name = m.name.drop(3)
            name {
                node.isStatic()

                matcher { (owner, field) ->
                    if (field.name == name) return@matcher true

                    val init = owner.methodByName("<clinit>") ?: return@matcher false
                    val ref = init.references.firstOrNull {
                        it.opcode == PUTSTATIC &&
                                it.name == field.name &&
                                it.owner == owner.name
                    } ?: return@matcher false

                    val ctorName = ref.previousOrNull<TypeInsnNode> { it.opcode == NEW && it.desc == owner.name }
                        ?.nextOrNull<LdcInsnNode> { it.cst is String } ?: return@matcher false

                    name == ctorName.cst
                }
            }
        }
    }

    extra()
}