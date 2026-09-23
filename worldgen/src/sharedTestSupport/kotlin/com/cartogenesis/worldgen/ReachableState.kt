package com.cartogenesis.worldgen

import java.lang.reflect.Field
import java.lang.reflect.Modifier
import java.util.IdentityHashMap

/**
 * Everything a test could write to in a generated world, read by reflection: a digest of it, a
 * deep copy of it, and the identities of its mutable containers.
 *
 * Reflection rather than a hand-written list of fields, because the list is exactly what goes stale:
 * a stage that gains an array would drop out of a hand-kept digest without anyone noticing, and a
 * shared world written through that array would then pass every check. Walked this way, a new field
 * is covered the day it is added, and a type the walk does not know how to read stops it with the
 * path it was found at rather than being skipped.
 *
 * What the walk reads: every instance field of every object whose class is this project's or the
 * Kotlin library's, every element of every array, list, set and map, and the contents of every
 * primitive array. Strings, boxed primitives and enum constants are values and are read as values.
 * Anything else — a JDK object that is not a collection, a thread, a stream — is refused.
 */
internal object ReachableState {

    /**
     * One digest per branch of [root], keyed by the branch's path.
     *
     * A branch is everything reachable through one property two levels down from the root — for a
     * world, `climate.temperature` or `rivers.rivers` — so a change is reported where it happened
     * rather than as "the world changed". Properties that hold a plain value at the first level
     * (a world's config) are one branch each. The digests are 64-bit and read the raw bits of every
     * float, so a NaN written over a NaN with a different payload still counts as a write.
     */
    fun digestsByBranch(root: Any): Map<String, Long> {
        val digests = LinkedHashMap<String, Long>()
        val walk = Walk()
        for (field in instanceFieldsOf(root.javaClass)) {
            val child = field.get(root)
            val firstLevel = field.name
            if (child == null || isValue(child) || !isComposite(child)) {
                digests[firstLevel] = walk.digestOf(child, firstLevel)
                continue
            }
            for (grandchildField in instanceFieldsOf(child.javaClass)) {
                val path = "$firstLevel.${grandchildField.name}"
                digests[path] = walk.digestOf(grandchildField.get(child), path)
            }
        }
        return digests
    }

    /** How many bytes of arrays [root] holds, counting a shared array once. */
    fun arrayBytes(root: Any): Long {
        val walk = Walk()
        walk.digestOf(root, "")
        return walk.arrayBytes
    }

    /**
     * The mutable containers reachable from [root] — every array, list, set and map, and every
     * object with a field — by identity. Two worlds whose sets are disjoint cannot write into each
     * other, which is what a deep copy promises.
     */
    fun mutableContainers(root: Any): Set<Any> {
        val walk = Walk()
        walk.digestOf(root, "")
        return walk.containers
    }

    /**
     * A copy of [root] that shares no mutable container with it, built field by field.
     *
     * Objects are allocated without running their constructors and have every field copied, because a
     * data class's `copy()` is shallow — it would hand a writer the very arrays the original holds.
     * Arrays are copied element by element, lists, sets and maps into fresh ones in the same order,
     * and an object reached twice is copied once, so a shared array stays shared inside the copy.
     */
    @Suppress("UNCHECKED_CAST")
    fun <T : Any> deepCopy(root: T): T = Copy().of(root, "") as T

    private class Walk {
        private val firstVisit = IdentityHashMap<Any, Int>()
        val containers: MutableSet<Any> = java.util.Collections.newSetFromMap(IdentityHashMap())
        var arrayBytes = 0L
            private set

        fun digestOf(value: Any?, path: String): Long {
            var digest = SEED
            digest = mix(digest, value, path)
            return digest
        }

        private fun mix(digest: Long, value: Any?, path: String): Long {
            if (value == null) return step(digest, NULL_MARK)
            if (isValue(value)) return step(digest, valueBits(value))
            val seenAt = firstVisit[value]
            if (seenAt != null) return step(step(digest, BACK_REFERENCE_MARK), seenAt.toLong())
            firstVisit[value] = firstVisit.size
            containers.add(value)
            var result = step(digest, kindOf(value).hashCode().toLong())
            when (value) {
                is FloatArray -> {
                    arrayBytes += 4L * value.size
                    result = step(result, value.size.toLong())
                    for (element in value) result = step(result, element.toRawBits().toLong())
                }
                is DoubleArray -> {
                    arrayBytes += 8L * value.size
                    result = step(result, value.size.toLong())
                    for (element in value) result = step(result, element.toRawBits())
                }
                is IntArray -> {
                    arrayBytes += 4L * value.size
                    result = step(result, value.size.toLong())
                    for (element in value) result = step(result, element.toLong())
                }
                is LongArray -> {
                    arrayBytes += 8L * value.size
                    result = step(result, value.size.toLong())
                    for (element in value) result = step(result, element)
                }
                is ShortArray -> {
                    arrayBytes += 2L * value.size
                    result = step(result, value.size.toLong())
                    for (element in value) result = step(result, element.toLong())
                }
                is ByteArray -> {
                    arrayBytes += value.size.toLong()
                    result = step(result, value.size.toLong())
                    for (element in value) result = step(result, element.toLong())
                }
                is CharArray -> {
                    arrayBytes += 2L * value.size
                    result = step(result, value.size.toLong())
                    for (element in value) result = step(result, element.code.toLong())
                }
                is BooleanArray -> {
                    arrayBytes += value.size.toLong()
                    result = step(result, value.size.toLong())
                    for (element in value) result = step(result, if (element) 1L else 0L)
                }
                is Array<*> -> {
                    arrayBytes += REFERENCE_BYTES * value.size
                    result = step(result, value.size.toLong())
                    value.forEachIndexed { index, element -> result = mixElement(result, element, path, index) }
                }
                is Collection<*> -> {
                    result = step(result, value.size.toLong())
                    value.forEachIndexed { index, element -> result = mixElement(result, element, path, index) }
                }
                is Map<*, *> -> {
                    result = step(result, value.size.toLong())
                    for ((key, element) in value) {
                        result = mix(result, key, "$path{key}")
                        result = mix(result, element, "$path[$key]")
                    }
                }
                is Lazy<*> -> result = mix(result, value.value, "$path.value")
                else -> {
                    requireReadable(value.javaClass, path)
                    for (field in instanceFieldsOf(value.javaClass)) {
                        result = mix(result, field.get(value), "$path.${field.name}")
                    }
                }
            }
            return result
        }

        /**
         * An element of an array or a collection. Values are mixed in directly, because a biome map
         * is a quarter of a million of them and naming each one's path would cost more than reading it.
         */
        private fun mixElement(digest: Long, element: Any?, parentPath: String, index: Int): Long = when {
            element == null -> step(digest, NULL_MARK)
            isValue(element) -> step(digest, valueBits(element))
            else -> mix(digest, element, "$parentPath[$index]")
        }
    }

    private class Copy {
        private val copies = IdentityHashMap<Any, Any>()

        fun of(value: Any?, path: String): Any? {
            if (value == null || isValue(value)) return value
            copies[value]?.let { return it }
            val copy: Any = when (value) {
                is FloatArray -> value.copyOf()
                is DoubleArray -> value.copyOf()
                is IntArray -> value.copyOf()
                is LongArray -> value.copyOf()
                is ShortArray -> value.copyOf()
                is ByteArray -> value.copyOf()
                is CharArray -> value.copyOf()
                is BooleanArray -> value.copyOf()
                is Array<*> -> {
                    val fresh = java.lang.reflect.Array.newInstance(
                        value.javaClass.componentType, value.size
                    ) as Array<Any?>
                    copies[value] = fresh
                    value.forEachIndexed { index, element -> fresh[index] = element(element, path, index) }
                    fresh
                }
                is Set<*> -> {
                    val fresh = LinkedHashSet<Any?>(value.size)
                    copies[value] = fresh
                    value.forEachIndexed { index, element -> fresh.add(element(element, path, index)) }
                    fresh
                }
                is Collection<*> -> {
                    val fresh = ArrayList<Any?>(value.size)
                    copies[value] = fresh
                    value.forEachIndexed { index, element -> fresh.add(element(element, path, index)) }
                    fresh
                }
                is Map<*, *> -> {
                    val fresh = LinkedHashMap<Any?, Any?>(value.size)
                    copies[value] = fresh
                    for ((key, element) in value) fresh[of(key, "$path{key}")] = of(element, "$path[$key]")
                    fresh
                }
                is Lazy<*> -> lazyOf(of(value.value, "$path.value"))
                else -> {
                    requireReadable(value.javaClass, path)
                    val fresh = allocateWithoutConstructor(value.javaClass)
                    copies[value] = fresh
                    for (field in instanceFieldsOf(value.javaClass)) {
                        field.set(fresh, of(field.get(value), "$path.${field.name}"))
                    }
                    fresh
                }
            }
            copies[value] = copy
            return copy
        }

        /** An element of an array or a collection; a value is its own copy, and needs no path. */
        private fun element(element: Any?, parentPath: String, index: Int): Any? =
            if (element == null || isValue(element)) element else of(element, "$parentPath[$index]")
    }

    /**
     * What a container is, for the digest: its class, except that every set reads as a set, every
     * other collection as a list, and every map and lazy value as one of those, because a deep copy
     * puts a list's elements in a fresh `ArrayList` whatever list held them, and the copy must read
     * the same as its original.
     */
    private fun kindOf(value: Any): String = when (value) {
        is Set<*> -> "set"
        is Collection<*> -> "list"
        is Map<*, *> -> "map"
        is Lazy<*> -> "lazy"
        else -> value.javaClass.name
    }

    /** Values rather than containers: nothing a test can write to lives inside one. */
    private fun isValue(value: Any): Boolean =
        value is String || value is Number && value.javaClass.name.startsWith("java.lang.") ||
            value is Boolean || value is Char || value is Enum<*> || value is Unit || value is Class<*>

    private fun isComposite(value: Any): Boolean =
        !value.javaClass.isArray && value !is Collection<*> && value !is Map<*, *> &&
            isOwnOrKotlinClass(value.javaClass)

    private fun valueBits(value: Any): Long = when (value) {
        is Float -> value.toRawBits().toLong()
        is Double -> value.toRawBits()
        is Number -> value.toLong()
        is Boolean -> if (value) 1L else 0L
        is Char -> value.code.toLong()
        is Enum<*> -> value.javaClass.name.hashCode().toLong() * ENUM_SPREAD + value.ordinal
        is String -> value.hashCode().toLong() * STRING_SPREAD + value.length
        else -> value.toString().hashCode().toLong()
    }

    private fun isOwnOrKotlinClass(type: Class<*>): Boolean =
        type.name.startsWith("com.cartogenesis.") || type.name.startsWith("kotlin.")

    private fun requireReadable(type: Class<*>, path: String) {
        check(isOwnOrKotlinClass(type)) {
            "cannot read a ${type.name} at '$path': ReachableState reads this project's classes, " +
                "Kotlin's, arrays, collections and values. Teach it this type on purpose rather " +
                "than letting what it holds go unchecked."
        }
    }

    private val fieldsByClass = java.util.concurrent.ConcurrentHashMap<Class<*>, List<Field>>()

    private fun instanceFieldsOf(type: Class<*>): List<Field> = fieldsByClass.getOrPut(type) {
        generateSequence(type) { it.superclass }
            .takeWhile { it != Any::class.java }
            .flatMap { it.declaredFields.asSequence() }
            .filter { !Modifier.isStatic(it.modifiers) }
            .onEach { it.isAccessible = true }
            .toList()
    }

    private val unsafe: sun.misc.Unsafe by lazy {
        val field = sun.misc.Unsafe::class.java.getDeclaredField("theUnsafe")
        field.isAccessible = true
        field.get(null) as sun.misc.Unsafe
    }

    private fun allocateWithoutConstructor(type: Class<*>): Any = unsafe.allocateInstance(type)

    private fun step(digest: Long, bits: Long): Long {
        val mixed = (digest xor bits) * MULTIPLIER
        return mixed xor (mixed ushr SHIFT)
    }

    /** Arbitrary odd starting value; any fixed one would do. */
    private const val SEED = 0x2545F4914F6CDD1DL

    /** The 64-bit golden-ratio multiplier, the usual choice for spreading bits in one multiply. */
    private const val MULTIPLIER = -0x61c8864680b583ebL

    /** Folds the high half back down after the multiply, so low-bit changes reach every bit. */
    private const val SHIFT = 29

    private const val NULL_MARK = 0x6E756C6CL
    private const val BACK_REFERENCE_MARK = 0x72656672L
    private const val ENUM_SPREAD = 1_000_003L
    private const val STRING_SPREAD = 1_000_033L

    /** A reference with compressed pointers, which is what a heap under 32 GB uses. */
    private const val REFERENCE_BYTES = 4L
}
