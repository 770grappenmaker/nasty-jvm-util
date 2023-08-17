package com.grappenmaker.jvmutil

import org.objectweb.asm.Opcodes
import org.objectweb.asm.Type
import org.objectweb.asm.tree.*
import org.objectweb.asm.util.TraceClassVisitor
import java.lang.instrument.ClassFileTransformer
import java.lang.instrument.Instrumentation
import java.lang.invoke.MethodType
import java.lang.reflect.Method
import java.security.ProtectionDomain
import kotlin.properties.ReadOnlyProperty
import kotlin.reflect.KProperty

/**
 * Creates and registers a [FinderContext] for this [Instrumentation]
 */
public fun Instrumentation.finders(debug: Boolean = false, skipTransform: Set<String> = setOf()): FinderContext =
    FinderContext(debug, skipTransform).also { it.registerWith(this) }

/**
 * Context for building a set of [ClassFinder]s, and subsequently transforming classes with them
 * Can accept new classes being loaded. Can be installed using [registerWith]
 * If [debug] is `true` then a [TraceClassVisitor] will be used when a class gets transformed.
 * Classes in packages (binary name) in [skipTransform] will never be transformed
 */
public class FinderContext(
    private val debug: Boolean = false,
    private val skipTransform: Set<String> = setOf()
) {
    public val finders: MutableList<ClassFinder> = mutableListOf()
    private var instrumentation: Instrumentation? = null

    public val isRegistered: Boolean get() = instrumentation != null

    /**
     * Allows you to apply a finder on a [ClassNode].
     * If [clazz] is null, transformation will not work
     */
    public fun byNode(
        node: ClassNode,
        clazz: Class<*>? = null,
        requireMatch: Boolean = false,
        block: ClassContext.() -> Unit
    ): ClassFinder {
        require(clazz == null || node.name == clazz.internalName) { "node and clazz do not match!" }

        val finder = ClassFinder(ClassContext().also(block))
        when (finder.offer(node)) {
            ClassFinder.NoTransformRequest -> {
                if (clazz != null) {
                    // Add finder temporarily (so the retransformer can handle it)
                    finders += finder

                    runCatching { instrumentation?.retransformClasses(clazz) }.onFailure {
                        println("Failed to retransform ClassNode on demand:")
                        it.printStackTrace()
                    }

                    finders -= finder // to avoid concurrent modifications
                }
            }

            ClassFinder.Skip -> error("Newly initialized ClassFinder shouldn't skip!")
            is ClassFinder.TransformRequest ->
                error("ClassFinder shouldn't return a TransformRequest when transform = false")

            ClassFinder.NoMatch -> if (requireMatch) error("Finder for ${node.name} didn't match")
            else -> {
                /* we don't really care */
            }
        }

        return finder
    }

    /**
     * Allows you to apply a finder on a [ClassNode].
     */
    public fun byNode(
        node: ClassNode,
        classLoader: ClassLoader,
        requireMatch: Boolean = false,
        block: ClassContext.() -> Unit
    ): ClassFinder = byNode(node, classLoader.forName(node.name.replace('/', '.')), requireMatch, block)

    /**
     * Allows you to register a [ClassFinder], defining matchers with
     * a [ClassContext] [block]
     */
    public fun findClass(block: ClassContext.() -> Unit): ClassFinder =
        ClassFinder(ClassContext().also(block)).also { finders += it }

    // Requests finders to visit the class and maybe request transformation
    private fun offer(node: ClassNode, transform: Boolean) = finders.mapNotNull { f ->
        runCatching { f.offer(node, transform) }
            .onFailure { println("Failed to offer class ${node.name} to $f"); it.printStackTrace() }
            .getOrNull()
    }

    private fun offer(node: ClassNode, name: String, transform: Boolean = false) =
        runCatching { offer(node, transform) }
            .onFailure { println("Failed to offer class $name"); it.printStackTrace() }

    /**
     * Transforms a given class, by [className] and [bytes], that gets loaded in a certain [loader], using
     * this [FinderContext]
     */
    public fun transform(
        loader: ClassLoader,
        className: String,
        bytes: ByteArray
    ): ByteArray? {
        // Stop if this class is a system class
        // (this prevents circular constructs and incorrect transformation)
        val binaryName = className.replace('/', '.')
        if (isSystemClass(binaryName) || skipTransform.any { binaryName.startsWith(it) }) return null

        // Find the class node
        val node = bytes.asClassNode()

        // Offer this class
        val result = offer(node, className, transform = true).getOrNull() ?: return null

        // Filter out all transform requests
        val transformRequests = result.filterIsInstance<ClassFinder.TransformRequest>()

        // If no transform requests, end the transformation
        if (transformRequests.isEmpty()) return null

        // Find out if frames should be expanded
        val shouldExpand = transformRequests.any { it.shouldExpand }
        val computeFrames = transformRequests.all { it.allowComputeFrames }

        // Find all method transforms
        val transforms = transformRequests.flatMap { it.transforms }

        // Don't transform when no transforms were yielded
        if (transforms.isEmpty()) return null

        if (debug) println("Transforming \"$className\" (processing ${transforms.size} transforms)...")

        return runCatching {
            node.transformDefault(
                transforms,
                originalBuffer = bytes,
                loader,
                computeFrames = computeFrames,
                expand = shouldExpand,
                debug = debug
            )
        }.onFailure {
            println("Failed to transform class $className:")
            it.printStackTrace()
        }.getOrNull()
    }

    /**
     * Registers this finding context with an instrumentation instance.
     * That is, it goes over all loaded classes and offers it to finders,
     * as well as add a hook to classloading.
     */
    public fun registerWith(inst: Instrumentation) {
        // Save the instrumentation for later use
        require(instrumentation == null) { "A FinderContext can only be registered once!" }
        instrumentation = inst

        // Register transformer
        inst.addTransformer(object : ClassFileTransformer {
            override fun transform(
                loader: ClassLoader,
                className: String,
                classBeingRedefined: Class<*>?,
                protectionDomain: ProtectionDomain,
                classfileBuffer: ByteArray
            ) = transform(loader, className, classfileBuffer)
        })
    }
}

/**
 * A [ClassFinder] is an entry point for
 * - Accessing a class, defined by a [ClassContext]
 * - Accessing methods, defined by [MethodContext]s
 * - Accessing fields, defined by [FieldContext]s
 */
public class ClassFinder(internal val context: ClassContext) : ElementFinder<ClassNode> {
    private var value: ClassNode? = null
    private var ranFoundHooks = false
    private val foundHooks = mutableListOf<(ClassNode) -> Unit>()
    public val methods: MethodsFinder = MethodsFinder(this)
    public val fields: FieldsFinder = FieldsFinder(this)

    @Suppress("NOTHING_TO_INLINE")
    private inline fun <T : Any, V : Matchable<T>> EntityContainer<T, V>.findAll(
        target: MatchContainer<T>,
        searchIn: List<T>
    ): List<Pair<T, V>>? = entities.mapNotNull { (name, ctx) ->
        val entity = searchIn.find { ctx.matches(it) }
            ?: if (ctx.allowMissing) return@mapNotNull null else return@findAll null

        target.offer(name, entity)
        entity to ctx
    }

    internal fun offer(node: ClassNode, transform: Boolean = false): OfferResult {
        // If we are not transforming, and we already have a value, skip early
        if (!transform && value != null) return Skip

        // If the node name does not match with a potential current value, skip
        if (value != null && value?.name != node.name) return Skip

        // First, validate the top-level class matchers
        if (!context.matches(node)) return NoMatch

        // Extract all method/field data
        val methodData = node.methodData
        val fieldData = node.fieldData

        val elementNotMatched = { reset(); NoMatch }
        val foundMethods = context.methodsContext.findAll(methods, methodData) ?: return elementNotMatched()
        val foundFields = context.fieldsContext.findAll(fields, fieldData) ?: return elementNotMatched()

        // If there are supposed to be method/field matches, but there aren't enough (because allowMissing)
        // obviously deny it since otherwise different classes can match
        // TODO: this mechanism is really hacky
        val foundCount = foundMethods.size + foundFields.size
        val entityCount = context.methodsContext.entities.size + context.fieldsContext.entities.size

        if (
            (context.methodsContext.entities.isNotEmpty() || context.fieldsContext.entities.isNotEmpty()) &&
            foundCount.toDouble() / entityCount < context.existingThreshold
        ) return elementNotMatched()

        // Found it!
        value = node

        // Also handle all found hooks
        if (!ranFoundHooks) {
            ranFoundHooks = true
            (foundHooks + context.foundHooks).forEach {
                runCatching { it(node) }.onFailure {
                    it.printStackTrace()
                    println("Found hook for class ${node.name} was unsuccessful!")
                }
            }
        }

        // Small optimization
        if (!transform) return NoTransformRequest

        // Find all class transforms
        val classTransformations = context.transformations.flatMap { ClassTransformContext(node).also(it).transforms }

        // Find all method transform contexts
        val transformContexts = foundMethods.flatMap { (data, ctx) ->
            ctx.transformations.map { MethodTransformContext(data).also(it) }
        }

        // Find out if the frames should be expanded
        val shouldExpand = transformContexts.any { it.shouldExpandFrames }
        val allowComputeFrames = transformContexts.all { it.allowComputeFrames }

        // Convert all method transformations to class transformations
        val methodTransformations = transformContexts.map { it.asClassTransform() }

        // Accumulate all transformations and return the result
        val allTransformations = classTransformations + methodTransformations

        // Return correct transform result
        return if (allTransformations.isEmpty()) NotInterested
        else TransformRequest(allTransformations, shouldExpand, allowComputeFrames)
    }

    override fun reset() {
        value = null
        methods.reset()
        fields.reset()
    }

    internal sealed class OfferResult

    // Returned when transform = true and this finder is interested in transforming
    internal class TransformRequest(
        val transforms: List<ClassTransform>,
        val shouldExpand: Boolean,
        val allowComputeFrames: Boolean
    ) : OfferResult()

    // Returned when the class was found but the finder is not interested in transforming
    internal object NotInterested : OfferResult()

    // Returned when the class didn't match this finder
    internal object NoMatch : OfferResult()

    // Returned when transform = false, but there was interest to transform
    internal object NoTransformRequest : OfferResult()

    // Returned when transform = false and the finder already resolved a class
    internal object Skip : OfferResult()

    /**
     * Assumes this [ClassFinder] has found the requested class/methods/fields
     */
    override fun assume(): ClassNode = value ?: error("Didn't find the correct class, assumption failed!")

    /**
     * Returns the found value or null
     */
    override fun nullable(): ClassNode? = value

    /**
     * Adds a listener for when the class has been found
     */
    public fun onFound(handler: (ClassNode) -> Unit) {
        foundHooks += handler
    }
}

public interface MatchContainer<T : Any> : Reset {
    public val contents: MutableMap<String, ElementFinder<T>>

    public fun offer(name: String, node: T) {
        (contents[name] as? BoxElementFinder<T>)?.value = node
    }

    public operator fun getValue(thisRef: Nothing?, property: KProperty<*>): ElementFinder<T> {
        val name = property.name
        return contents[name] ?: error("An entity with name $name in $this does not exist!")
    }

    public operator fun get(name: String): ReadOnlyProperty<Nothing?, ElementFinder<T>> =
        ReadOnlyProperty { _, _ -> contents[name] ?: error("A finder with name $name does not exist!") }

    override fun reset() {
        contents.values.forEach { it.reset() }
    }
}

/**
 * Accessor for finding [MethodData]. Can be delegated to by properties
 */
public class MethodsFinder(private val finder: ClassFinder) : MatchContainer<MethodData> {
    override val contents: MutableMap<String, ElementFinder<MethodData>> = finder.context
        .methodsContext.entities.mapValues { BoxElementFinder<MethodData>() }
        .toMutableMap()

    /**
     * Returns a delegate property that finds a method by a given matcher, lazily
     */
    public fun late(block: MethodContext.() -> Unit): ReadOnlyProperty<Nothing?, ElementFinder<MethodData>> =
        object : ReadOnlyProperty<Nothing?, ElementFinder<MethodData>> {
            private val ef = BoxElementFinder<MethodData>()
            override fun getValue(thisRef: Nothing?, property: KProperty<*>): ElementFinder<MethodData> {
                if (ef.value == null) {
                    val context = MethodContext().also(block)
                    ef.value = finder().methodData.find { context.matches(it) }
                }

                return ef
            }
        }
}

/**
 * Accessor for finding [FieldData]. Can be delegated to by properties
 */
public class FieldsFinder(private val finder: ClassFinder) : MatchContainer<FieldData> {
    override val contents: MutableMap<String, ElementFinder<FieldData>> = finder.context
        .fieldsContext.entities.mapValues { BoxElementFinder<FieldData>() }
        .toMutableMap()

    /**
     * Returns a delegate property that finds a field by a given matcher, lazily
     */
    public fun late(block: FieldContext.() -> Unit): ReadOnlyProperty<Nothing?, ElementFinder<FieldData>> =
        object : ReadOnlyProperty<Nothing?, ElementFinder<FieldData>> {
            private val ef = BoxElementFinder<FieldData>()
            override fun getValue(thisRef: Nothing?, property: KProperty<*>): ElementFinder<FieldData> {
                if (ef.value == null) {
                    val context = FieldContext().also(block)
                    ef.value = finder().fieldData.find { context.matches(it) }
                }

                return ef
            }
        }
}

public interface Reset {
    public fun reset()
}

/**
 * Container class to find a specific element, [T]. Like rusts `Option<T>`, but simpler
 */
public interface ElementFinder<T : Any> : Reset {
    /**
     * Assumes this [ElementFinder] has found the requested element
     */
    public fun assume(): T

    /**
     * Returns the found value or null
     */
    public fun nullable(): T?

    /**
     * Whether this [ElementFinder] has a value or not
     */
    public val hasValue: Boolean get() = nullable() != null
}

public data class BoxElementFinder<T : Any>(internal var value: T? = null) : ElementFinder<T> {
    override fun assume(): T = value ?: error("No element has been found for $this")
    override fun nullable(): T? = value

    override fun reset() {
        value = null
    }
}

public data class LazyElementFinder<T : Any>(private val supplier: () -> T?) : ElementFinder<T> {
    private var value: T? = null

    private fun tryUpdate() = supplier()?.also { value = it }
    override fun assume(): T = value ?: tryUpdate() ?: error("No element has been found yet!")
    override fun nullable(): T? = value ?: tryUpdate()
    override fun reset() {
        value = null
    }
}

/**
 * Equivalent of [ElementFinder.assume]
 */
public operator fun <T : Any> ElementFinder<T>.invoke(): T = assume()

public fun <T : Any, O : Any> ElementFinder<T>.map(block: (T) -> O): LazyElementFinder<O> =
    LazyElementFinder { nullable()?.let(block) }

/**
 * Marker class that allows for defining english-like matchers on the constant pool
 */
public object ConstantsMarker

/**
 * Marker class that allows for defining english-like matchers on the strings of a class
 */
public object StringsMarker

/**
 * Marker class that allows for defining english-like matchers on a class
 */
public object ClassNodeMarker

/**
 * Marker class that allows for defining english-like matchers on a method
 */
public object MethodNodeMarker

/**
 * Marker class that allows for defining english-like matchers on a field
 */
public object FieldNodeMarker

/**
 * Marker class that allows for defining english-like matchers on arguments of a method
 */
public object ArgumentsMarker

/**
 * Marker class that allows for referencing the class that is being matched
 */
public object SelfMarker

/**
 * Class Matcher predicate
 */
public typealias ClassMatcher = (ClassNode) -> Boolean

/**
 * Method Matcher predicate
 */
public typealias MethodMatcher = (MethodData) -> Boolean

/**
 * Field Matcher predicate
 */
public typealias FieldMatcher = (FieldData) -> Boolean

/**
 * Method Invocation Matcher predicate
 */
public typealias CallMatcher = (MethodInsnNode) -> Boolean

/**
 * Field Reference Matcher predicate
 */
public typealias ReferenceMatcher = (FieldInsnNode) -> Boolean

/**
 * Marker annotation for defining the finding DSL
 */
@DslMarker
private annotation class FindingDSL

/**
 * DSL for defining matchers for a [ClassNode]
 */
@FindingDSL
public class ClassContext {
    /**
     * Allows you to reference infix functions of [ConstantsMarker]
     */
    public val constants: ConstantsMarker = ConstantsMarker

    /**
     * Allows you to reference infix functions of [StringsMarker]
     */
    public val strings: StringsMarker = StringsMarker

    /**
     * Allows you to reference infix functions of [ClassNodeMarker]
     */
    public val node: ClassNodeMarker = ClassNodeMarker

    /**
     * Allows you to specify (0-1) the amount of entities that at least need to be there
     * (if there are even any to match). It is required for an entity to have the allowMissing flag
     * for this number to even be used. Defaults to 1 (all entities must exist).
     * The zero (0) value means it does not check anything (therefore it will only check for allowMissing)
     * Negative values do not have defined behaviour.
     */
    public var existingThreshold: Double = 1.0

    private val matchers = mutableListOf<ClassMatcher>()
    internal val methodsContext = MethodsContext()
    internal val fieldsContext = FieldsContext()
    internal val transformations = mutableListOf<ClassTransformContext.() -> Unit>()
    internal val foundHooks = mutableListOf<(ClassNode) -> Unit>()

    /**
     * Matches the constant pool of a class (must contain [cst])
     */
    public infix fun ConstantsMarker.has(cst: Any?) {
        matchers += { cst in it.constants }
    }

    /**
     * Matches the strings of the constant pool of a class (must contain [s])
     */
    public infix fun StringsMarker.has(s: String) {
        matchers += { s in it.strings }
    }

    /**
     * Matches the strings of the constant pool of a class (a string must contain [part])
     */
    public infix fun StringsMarker.hasPartial(part: String) {
        matchers += { node -> node.strings.any { it.contains(part) } }
    }

    /**
     * Matches the strings of the constant pool of a class (some string must match [matcher])
     */
    public infix fun StringsMarker.some(matcher: (String) -> Boolean) {
        matchers += { it.strings.any(matcher) }
    }

    /**
     * Matches the class to a given predicate
     */
    public infix fun ClassNodeMarker.match(matcher: ClassMatcher) {
        matchers += matcher
    }

    /**
     * Matches if the class is an enum
     */
    public fun ClassNodeMarker.isEnum() {
        matchers += { it.superName == "java/lang/Enum" }
    }

    /**
     * Matches if given access [flag] is present
     */
    public infix fun ClassNodeMarker.access(flag: Int) {
        matchers += { it.access and flag != 0 }
    }

    /**
     * Matches if the class is named [name]
     */
    public infix fun ClassNodeMarker.named(name: String) {
        matchers += { it.name == name }
    }

    /**
     * Matches if the class extends [name]
     */
    public infix fun ClassNodeMarker.extends(name: String) {
        matchers += { it.superName == name }
    }

    /**
     * Matches if the class implements [name]
     */
    public infix fun ClassNodeMarker.implements(name: String) {
        matchers += { name in it.interfaces }
    }

    /**
     * Matches if this class is `static`
     */
    public fun ClassNodeMarker.isStatic(): Unit = access(Opcodes.ACC_STATIC)

    /**
     * Matches if this class is `private`
     */
    public fun ClassNodeMarker.isPrivate(): Unit = access(Opcodes.ACC_PRIVATE)

    /**
     * Matches if this class is `final`
     */
    public fun ClassNodeMarker.isFinal(): Unit = access(Opcodes.ACC_FINAL)

    /**
     * Matches if this class is an `interface`
     */
    public fun ClassNodeMarker.isInterface(): Unit = access(Opcodes.ACC_INTERFACE)

    /**
     * Matches if this class is `public`
     */
    public fun ClassNodeMarker.isPublic(): Unit = access(Opcodes.ACC_PUBLIC)

    /**
     * Matches if this class is `abstract`
     */
    public fun ClassNodeMarker.isAbstract(): Unit = access(Opcodes.ACC_ABSTRACT)

    /**
     * Define methods using a [MethodContext]
     */
    public fun methods(block: MethodsContext.() -> Unit): Unit = methodsContext.block()

    /**
     * Define fields using a [FieldContext]
     */
    public fun fields(block: FieldsContext.() -> Unit): Unit = fieldsContext.block()

    /**
     * Allows you to transform this class when all matchers match
     */
    public fun transform(block: ClassTransformContext.() -> Unit) {
        transformations += block
    }

    /**
     * Allows you to transform all methods when all matchers match
     */
    public fun transformMethods(block: MethodTransformContext.() -> Unit) {
        transform { methodVisitor { parent, data -> MethodTransformContext(data).also(block).asMethodVisitor(parent) } }
    }

    /**
     * Allows you to transform all methods under a certain [condition]
     */
    public fun transformMethods(condition: (MethodData) -> Boolean, block: MethodTransformContext.() -> Unit) {
        transformMethods { if (condition(MethodData(owner, method))) block() }
    }

    /**
     * Allows you to register found hooks.
     * That is, when the [ClassFinder] has found a class, a given [hook] will execute
     */
    public fun onFound(hook: (ClassNode) -> Unit) {
        foundHooks += hook
    }

    /**
     * Checks if this [ClassContext] matches a given [node]
     */
    public fun matches(node: ClassNode): Boolean = matchers.all { it(node) }
}

public interface EntityContainer<T : Any, V : Matchable<T>> {
    public val entities: Map<String, V>
}

/**
 * DSL for defining [MethodContext]s
 */
@FindingDSL
public class MethodsContext : EntityContainer<MethodData, MethodContext> {
    override val entities: MutableMap<String, MethodContext> = mutableMapOf()

    /**
     * Defines a new method for this context
     */
    public operator fun String.invoke(block: MethodContext.() -> Unit) {
        entities += this to MethodContext().also(block)
    }

    private var unnamedCounter = 0
        get() = field++

    /**
     * Defines an unnamed method for this context
     */
    // Whoops unnamed methods are also named
    public fun unnamedMethod(block: MethodContext.() -> Unit): Unit = "__unnamed$unnamedCounter"(block)
}

/**
 * DSL for defining [FieldContext]s
 */
@FindingDSL
public class FieldsContext : EntityContainer<FieldData, FieldContext> {
    override val entities: MutableMap<String, FieldContext> = mutableMapOf()

    /**
     * Defines a new field for this context
     */
    public operator fun String.invoke(block: FieldContext.() -> Unit) {
        entities += this to FieldContext().also(block)
    }
}

public interface Matchable<T : Any> {
    public val allowMissing: Boolean get() = false
    public fun matches(on: T): Boolean
}

/**
 * DSL for defining matchers for [MethodData]
 */
@FindingDSL
public class MethodContext : Matchable<MethodData> {
    /**
     * Allows you to reference infix functions of [ConstantsMarker]
     */
    public val constants: ConstantsMarker = ConstantsMarker

    /**
     * Allows you to reference infix functions of [StringsMarker]
     */
    public val strings: StringsMarker = StringsMarker

    /**
     * Allows you to reference infix functions of [MethodNodeMarker]
     */
    public val method: MethodNodeMarker = MethodNodeMarker

    /**
     * Allows you to reference infix functions of [ArgumentsMarker]
     */
    public val arguments: ArgumentsMarker = ArgumentsMarker

    /**
     * Allows you to reference infix functions of [SelfMarker]
     */
    public val self: SelfMarker = SelfMarker

    /**
     * Allows you to declare that this method is optional
     */
    override var allowMissing: Boolean = false

    private val matchers = mutableListOf<MethodMatcher>()
    internal val transformations = mutableListOf<MethodTransformContext.() -> Unit>()

    /**
     * Matches the constant pool of a method (must contain [cst])
     */
    public infix fun ConstantsMarker.has(cst: Any?) {
        matchers += { cst in it.method.constants }
    }

    /**
     * Matches the strings of the constant pool of a method (must contain [s])
     */
    public infix fun StringsMarker.has(s: String) {
        matchers += { s in it.method.strings }
    }

    /**
     * [has] but with more strings
     */
    public infix fun StringsMarker.has(strings: List<String>) {
        matchers += { it.method.strings.containsAll(strings) }
    }

    /**
     * Matches the strings of the constant pool of a method (a string must contain [part])
     */
    public infix fun StringsMarker.hasPartial(part: String) {
        matchers += { (_, node) -> node.strings.any { it.contains(part) } }
    }

    /**
     * Matches the strings of the constant pool of a class (some string must match [matcher])
     */
    public infix fun StringsMarker.some(matcher: (String) -> Boolean) {
        matchers += { it.method.strings.any(matcher) }
    }

    /**
     * Matches the method for a given [matcher]
     */
    public infix fun MethodNodeMarker.match(matcher: MethodMatcher) {
        matchers += matcher
    }

    /**
     * Matches when the method has a given [descriptor]
     */
    public infix fun MethodNodeMarker.hasDesc(descriptor: String) {
        matchers += { it.method.desc == descriptor }
    }

    /**
     * Matches if this method calls a method defined by the [CallContext]
     */
    public infix fun MethodNodeMarker.calls(matcher: CallContext.() -> Unit) {
        val context = CallContext().also(matcher)
        matchers += { (_, method) -> method.calls { context.matches(it) } }
    }

    /**
     * Matches if this method calls a method identical to this method (desc and name)
     * This presumably matches calling super, and only if invokespecial
     */
    public fun MethodNodeMarker.callsSuper() {
        matchers += { (_, m) -> m.calls { it.name == m.name && it.desc == m.desc && it.opcode == Opcodes.INVOKESPECIAL } }
    }

    /**
     * Matches if this method references a field defined by the [ReferenceContext]
     */
    public infix fun MethodNodeMarker.references(matcher: ReferenceContext.() -> Unit) {
        val context = ReferenceContext().also(matcher)
        matchers += { (_, method) -> method.references { context.matches(it) } }
    }

    /**
     * Matches if this method calls a method defined by a [MethodDescription]
     */
    public infix fun MethodNodeMarker.calls(desc: MethodDescription) {
        matchers += { data -> data.asDescription().isSimilar(desc) }
    }

    /**
     * Matches if this method calls a method defined by [MethodData]
     */
    public infix fun MethodNodeMarker.calls(data: MethodData): Unit = calls(data.asDescription())

    /**
     * Matches if argument index [n] == [type]
     */
    @Suppress("unused")
    public fun ArgumentsMarker.nth(n: Int, type: Type) {
        matchers += { it.method.arguments.getOrNull(n) == type }
    }

    /**
     * Equivalent of `arguments.nth(n, type)`
     */
    public operator fun ArgumentsMarker.set(n: Int, type: Type) {
        matchers += { it.method.arguments.getOrNull(n) == type }
    }

    /**
     * Matches if any argument is a given [type]
     */
    public infix fun ArgumentsMarker.has(type: Type) {
        matchers += { type in it.method.arguments }
    }

    /**
     * Matches if this method has no arguments
     */
    public fun ArgumentsMarker.hasNone(): Unit = count(0)

    /**
     * Matches if this method has a given [amount] of arguments
     */
    public infix fun ArgumentsMarker.count(amount: Int) {
        matchers += { it.method.arguments.size == amount }
    }

    /**
     * Matches if the list of [arguments] matches exactly
     */
    public infix fun ArgumentsMarker.hasExact(arguments: List<Type>) {
        matchers += { it.method.arguments.toList() == arguments }
    }

    /**
     * Matches if this method returns [type]
     */
    public infix fun MethodNodeMarker.returns(type: Type) {
        matchers += { it.method.returnType == type }
    }

    /**
     * Matches if this method returns typeof [node]
     */
    public infix fun MethodNodeMarker.returns(node: ClassNode): Unit = returns(node.type)

    /**
     * Matches if this method returns itself
     */
    public infix fun MethodNodeMarker.returns(@Suppress("UNUSED_PARAMETER") self: SelfMarker) {
        matchers += { (owner, method) -> method.returnType.internalName == owner.name }
    }

    /**
     * Matches if this method returns a primitive
     */
    public fun MethodNodeMarker.returnsPrimitive() {
        matchers += { (_, m) -> m.returnType.isPrimitive }
    }

    /**
     * Checks if this method has a given access [flag]
     */
    public infix fun MethodNodeMarker.access(flag: Int) {
        matchers += { it.method.access and flag != 0 }
    }

    /**
     * Matches if this method is `static`
     */
    public fun MethodNodeMarker.isStatic(): Unit = access(Opcodes.ACC_STATIC)

    /**
     * Matches if this method is not `static`
     */
    public fun MethodNodeMarker.isVirtual() {
        matchers += { it.method.access and Opcodes.ACC_STATIC == 0 }
    }

    /**
     * Matches if this method is `private`
     */
    public fun MethodNodeMarker.isPrivate(): Unit = access(Opcodes.ACC_PRIVATE)

    /**
     * Matches if this method is `final`
     */
    public fun MethodNodeMarker.isFinal(): Unit = access(Opcodes.ACC_FINAL)

    /**
     * Matches if this method is `protected`
     */
    public fun MethodNodeMarker.isProtected(): Unit = access(Opcodes.ACC_PROTECTED)

    /**
     * Matches if this method is `public`
     */
    public fun MethodNodeMarker.isPublic(): Unit = access(Opcodes.ACC_PUBLIC)

    /**
     * Matches if this method is `abstract`
     */
    public fun MethodNodeMarker.isAbstract(): Unit = access(Opcodes.ACC_ABSTRACT)

    /**
     * Checks if a method has a given [name]
     */
    public infix fun MethodNodeMarker.named(name: String) {
        matchers += { it.method.name == name }
    }

    /**
     * Checks if a method is a constructor
     * That is, the name is equal to <init>
     */
    public fun MethodNodeMarker.isConstructor(): Unit = named("<init>")

    /**
     * Checks if a method is a static initializer
     * That is, the name is equal to <clinit>
     */
    public fun MethodNodeMarker.isStaticInit(): Unit = named("<clinit>")

    /**
     * Allows you to transform this method when all matchers match
     */
    public fun transform(block: MethodTransformContext.() -> Unit) {
        transformations += block
    }

    /**
     * Defines a matcher that is reevaluated every time
     * Useful when referencing other class finders
     */
    public fun matchLazy(block: MethodContext.() -> Unit) {
        matchers += { MethodContext().apply(block).matches(it) }
    }

    /**
     * Allows you to define custom matchers
     */
    public fun matcher(block: (MethodData) -> Boolean) {
        matchers += block
    }

    /**
     * Makes it never match
     */
    public fun noMatch() {
        matchers += { false }
    }

    /**
     * Checks if this [MethodContext] matches given [MethodData]
     */
    override fun matches(on: MethodData): Boolean = matchers.all { it(on) }
}

/**
 * DSL for defining matchers for a [MethodInsnNode]
 */
@FindingDSL
public class CallContext : Matchable<MethodInsnNode> {
    /**
     * Allows you to access infix functions of [MethodNodeMarker]
     */
    public val method: MethodNodeMarker = MethodNodeMarker

    /**
     * Allows you to access infix functions of [ArgumentsMarker]
     */
    public val arguments: ArgumentsMarker = ArgumentsMarker
    private val matchers = mutableListOf<CallMatcher>()

    /**
     * Matches when the called method has a given [name]
     */
    public infix fun MethodNodeMarker.named(name: String) {
        matchers += { it.name == name }
    }

    /**
     * Matches if argument [n] is typeof [type]
     */
    public operator fun ArgumentsMarker.set(n: Int, type: Type) {
        matchers += { Type.getArgumentTypes(it.desc).getOrNull(n) == type }
    }

    /**
     * Matches if any argument is of type [type]
     */
    public infix fun ArgumentsMarker.has(type: Type) {
        matchers += { type in Type.getArgumentTypes(it.desc) }
    }

    /**
     * Matches if this method has no arguments
     */
    public fun ArgumentsMarker.hasNone(): Unit = count(0)

    /**
     * Matches if this method has a given [amount] of arguments
     */
    public infix fun ArgumentsMarker.count(amount: Int) {
        matchers += { Type.getArgumentTypes(it.desc).size == amount }
    }

    /**
     * Matches if the list of [arguments] matches exactly
     */
    public infix fun ArgumentsMarker.hasExact(arguments: List<Type>) {
        matchers += {
            Type.getArgumentTypes(it.desc).zip(arguments)
                .all { (a, b) -> a.internalName == b.internalName }
        }
    }

    /**
     * Matches if this method call returns [type]
     */
    public infix fun MethodNodeMarker.returns(type: Type) {
        matchers += { Type.getReturnType(it.desc) == type }
    }

    /**
     * Matches if this method call is owned by [type]
     */
    public infix fun MethodNodeMarker.ownedBy(type: Type) {
        matchers += { it.owner == type.internalName }
    }

    /**
     * Matches if the given [matcher] matches the method call
     */
    public infix fun MethodNodeMarker.match(matcher: CallMatcher) {
        matchers += matcher
    }

    /**
     * Matches if the method is invoked with a given [opcode]
     */
    public infix fun MethodNodeMarker.calledWith(opcode: Int) {
        matchers += { it.opcode == opcode }
    }

    /**
     * Allows you to define custom matchers
     */
    public fun matcher(block: (MethodInsnNode) -> Boolean) {
        matchers += block
    }

    /**
     * Makes it never match
     */
    public fun noMatch() {
        matchers += { false }
    }

    /**
     * Checks if this [CallContext] matches a given [MethodInsnNode]
     */
    override fun matches(on: MethodInsnNode): Boolean = matchers.all { it(on) }
}

/**
 * DSL for defining matchers for a [FieldInsnNode]
 */
@FindingDSL
public class ReferenceContext : Matchable<FieldInsnNode> {
    /**
     * Allows you to access infix functions of [FieldNodeMarker]
     */
    public val field: FieldNodeMarker = FieldNodeMarker

    private val matchers = mutableListOf<ReferenceMatcher>()

    /**
     * Matches when the referenced field has a given [name]
     */
    public infix fun FieldNodeMarker.named(name: String) {
        matchers += { it.name == name }
    }

    /**
     * Matches if this field reference returns [type]
     */
    public infix fun FieldNodeMarker.isType(type: Type) {
        matchers += { Type.getType(it.desc) == type }
    }

    /**
     * Matches if this field reference is owned by [type]
     */
    public infix fun FieldNodeMarker.ownedBy(type: Type) {
        matchers += { it.owner == type.internalName }
    }

    /**
     * Matches if the given [matcher] matches the field reference
     */
    public infix fun FieldNodeMarker.match(matcher: ReferenceMatcher) {
        matchers += matcher
    }

    /**
     * Matches if the field is referenced with a given [opcode]
     */
    public infix fun FieldNodeMarker.calledWith(opcode: Int) {
        matchers += { it.opcode == opcode }
    }

    /**
     * Allows you to define custom matchers
     */
    public fun matcher(block: (FieldInsnNode) -> Boolean) {
        matchers += block
    }

    /**
     * Makes it never match
     */
    public fun noMatch() {
        matchers += { false }
    }

    /**
     * Checks if this [ReferenceContext] matches a given [FieldInsnNode]
     */
    override fun matches(on: FieldInsnNode): Boolean = matchers.all { it(on) }
}

/**
 * DSL for defining matchers for [FieldData]
 */
@FindingDSL
public class FieldContext : Matchable<FieldData> {
    /**
     * Allows you to access infix functions of [FieldNodeMarker]
     */
    public val node: FieldNodeMarker = FieldNodeMarker

    /**
     * Allows you to declare that this field is optional
     */
    override var allowMissing: Boolean = false

    private val matchers = mutableListOf<FieldMatcher>()

    /**
     * Matches if this field is typeof [type]
     */
    public infix fun FieldNodeMarker.isType(type: Type) {
        matchers += { it.field.desc == type.descriptor }
    }

    /**
     * Matches if this field is typeof [desc]
     */
    public infix fun FieldNodeMarker.isType(desc: String) {
        matchers += { it.field.desc == desc }
    }

    /**
     * Matches if this field is typeof the given [node]
     */
    public infix fun FieldNodeMarker.isType(node: ClassNode): Unit = isType(node.type)

    /**
     * Matches if this `public static final` field has a given [constant] as value
     */
    public infix fun FieldNodeMarker.staticValue(constant: Any?) {
        matchers += { it.field.value == constant }
    }

    /**
     * Matches if this field has given access [flags]
     */
    public infix fun FieldNodeMarker.access(flags: Int) {
        matchers += { it.field.access and flags != 0 }
    }

    public infix fun FieldNodeMarker.named(name: String) {
        matchers += { it.field.name == name }
    }

    /**
     * Matches if this field is `static`
     */
    public fun FieldNodeMarker.isStatic(): Unit = access(Opcodes.ACC_STATIC)

    /**
     * Matches if this field is `private`
     */
    public fun FieldNodeMarker.isPrivate(): Unit = access(Opcodes.ACC_PRIVATE)

    /**
     * Matches if this field is `final`
     */
    public fun FieldNodeMarker.isFinal(): Unit = access(Opcodes.ACC_FINAL)

    /**
     * Matches if this field is `public`
     */
    public fun FieldNodeMarker.isPublic(): Unit = access(Opcodes.ACC_PUBLIC)

    /**
     * Matches if this field is `protected`
     */
    public fun FieldNodeMarker.isProtected(): Unit = access(Opcodes.ACC_PROTECTED)

    /**
     * Matches on a given [matcher]
     */
    public infix fun FieldNodeMarker.match(matcher: (FieldData) -> Boolean) {
        matchers += matcher
    }

    /**
     * Allows you to define custom matchers
     */
    public fun matcher(block: (FieldData) -> Boolean) {
        matchers += block
    }

    /**
     * Defines a matcher that is reevaluated every time
     * Useful when referencing other class finders
     */
    public fun matchLazy(block: FieldContext.() -> Unit) {
        matchers += { FieldContext().apply(block).matches(it) }
    }

    /**
     * Makes it never match
     */
    public fun noMatch() {
        matchers += { false }
    }

    /**
     * Checks if this [FieldContext] matches given [FieldData]
     */
    override fun matches(on: FieldData): Boolean = matchers.all { it(on) }
}

/**
 * Wraps an owner-method pair into a datastructure for convenience
 */
public data class MethodData(val owner: ClassNode, val method: MethodNode)

/**
 * Wraps an owner-field pair into a datastructure for convenience
 */
public data class FieldData(val owner: ClassNode, val field: FieldNode)

/**
 * Utility to find a method with a [MethodContext] for a [ClassNode]
 */
public inline fun ClassNode.methodByFinder(block: MethodContext.() -> Unit): MethodData? {
    val context = MethodContext().apply(block)
    return methodData.find { context.matches(it) }
}

/**
 * Utility to find all method that match a [MethodContext] for a [ClassNode]
 */
public inline fun ClassNode.allMethodsByFinder(block: MethodContext.() -> Unit): List<MethodData> {
    val context = MethodContext().apply(block)
    return methodData.filter { context.matches(it) }
}

public fun MethodInsnNode.matches(block: CallContext.() -> Unit): Boolean = CallContext().also(block).matches(this)

public fun MethodsContext.named(name: String, block: MethodContext.() -> Unit = {}): Unit = name {
    method named name
    block()
}

public fun FieldsContext.named(name: String, block: FieldContext.() -> Unit = {}): Unit = name {
    node named name
    block()
}

public fun MethodsContext.namedTransform(name: String, block: MethodTransformContext.() -> Unit): Unit = name {
    method named name
    transform(block)
}

public fun MethodsContext.clinit(block: MethodContext.() -> Unit): Unit = "clinit" {
    method.isStaticInit()
    block()
}

public fun MethodsContext.clinitTransform(block: MethodTransformContext.() -> Unit): Unit = "clinit" {
    method.isStaticInit()
    transform(block)
}

private fun ClassLoader.loadInternal(name: String) = loadClass(name.replace('/', '.'))

public fun MethodData.asMethod(loader: ClassLoader = ClassLoader.getSystemClassLoader()): Method =
    loader.loadInternal(owner.name).getDeclaredMethod(
        method.name,
        *MethodType.fromMethodDescriptorString(method.desc, loader).parameterArray()
    ).also { it.isAccessible = true }

public fun MethodData.tryInvoke(receiver: Any? = null, vararg params: Any?, method: Method = asMethod()): Any =
    method(receiver, *params)

public fun ClassFinder.useStringInfo(): Unit = onFound { node ->
    val toString = node.methodByName("toString") ?: return@onFound
    val indy = toString.instructions.filterIsInstance<InvokeDynamicInsnNode>().lastOrNull() ?: return@onFound
    if (
        indy.bsm.owner != "java/lang/invoke/StringConcatFactory" ||
        indy.bsm.name != "makeConcatWithConstants"
    ) return@onFound

    val concat = indy.bsmArgs.firstOrNull() as? String ?: return@onFound
    val parts = concat.substringAfter('(').substringBeforeLast(')').split(", ")
    val names = parts.map { it.substringBefore('=') }
    val getters = toString.instructions.filter { it.opcode == Opcodes.GETFIELD || it.opcode == Opcodes.INVOKEVIRTUAL }

    if (names.size != getters.size) {
        println("Failed finding toString info for ${node.name}, sizes do not match")
        return@onFound
    }

    getters.forEachIndexed { idx, insn ->
        val fieldInsn = when (insn) {
            is FieldInsnNode -> insn
            is MethodInsnNode -> {
                if (insn.owner != node.name) return@forEachIndexed

                val getter = node.methodByInvoke(insn) ?: error("Should never happen")
                getter.instructions.filterIsInstance<FieldInsnNode>().singleOrNull() ?: return@forEachIndexed
            }

            else -> error("toString references in a weird way")
        }

        val fieldData = FieldData(node, node.fieldByName(fieldInsn.name) ?: error("Field not in <this>"))
        fields.contents[names[idx]] = BoxElementFinder(fieldData)
    }
}

public fun FinderContext.stringDataFinder(
    name: String,
    extra: ClassContext.() -> Unit = {},
): ClassFinder = findClass {
    methods {
        named("toString") { strings hasPartial "$name(" }
    }

    extra()
}.also { it.useStringInfo() }

public fun ClassFinder.addBuilderFields(normalClass: ClassFinder): Unit = onFound {
    fields.contents.forEach { (name, field) ->
        normalClass.fields.contents.getOrPut(name) {
            LazyElementFinder { normalClass().fieldData.find { it.field.name == field().field.name } }
        }
    }
}

public fun ClassContext.constantReplacement(from: Any, to: Any): Unit = methods {
    unnamedMethod {
        constants has from
        transform { replaceConstant(from, to) }
    }
}

public fun ClassContext.constantReplacements(map: Map<Any, Any>): Unit = methods {
    map.forEach { (from, to) ->
        unnamedMethod {
            constants has from
            transform { replaceConstant(from, to) }
        }
    }
}

public fun ClassContext.constantReplacements(vararg pairs: Pair<Any, Any>): Unit = constantReplacements(pairs.toMap())