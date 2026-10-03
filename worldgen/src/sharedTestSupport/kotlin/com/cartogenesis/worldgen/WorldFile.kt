package com.cartogenesis.worldgen

import com.cartogenesis.worldgen.model.WorldMap
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.EOFException
import java.lang.reflect.Modifier
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.IdentityHashMap

/**
 * A generated world written to bytes and read back exactly, for the test tier's world cache.
 *
 * The save codec belongs to `:cartography`, which `:worldgen` cannot depend on, and it writes what a
 * player's file needs rather than every array a test reads. So this walks the world the way
 * [ReachableState] does — every instance field of this project's and Kotlin's classes, every element
 * of every array and collection — and writes what it finds: primitive arrays in bulk, as their raw
 * bits, so a NaN's payload and a negative zero survive; objects by class and fields; and an object
 * met a second time as a reference to the first, so an array two results share is one array again
 * when read. A new field in any result is written the day it is added, with nothing to keep in step.
 *
 * Reading gives back what [ReachableState.deepCopy] would: the same classes allocated without their
 * constructors, lists as `ArrayList`, sets as `LinkedHashSet`, maps as `LinkedHashMap` in the same
 * order, and a lazy value as the value it held. A Kotlin `object` is read as its one instance, so a
 * comparison with it by identity still holds. Whether the round trip is exact is not taken on trust:
 * [WorldDiskCache] stores the fresh world's digests beside it and checks them on every read.
 *
 * The layout depends on the classes' fields, so a file is only read by the code that wrote it; the
 * cache keys every file by a hash of the generator's compiled classes for that reason.
 */
internal object WorldFile {

    /** Writes [world] to [output]. The caller buffers and closes the stream. */
    fun write(world: WorldMap, output: DataOutputStream) {
        Writer(output).value(world, "world")
        output.writeInt(END_MARK)
    }

    /** Reads the world [write] wrote, failing rather than returning anything incomplete. */
    fun read(input: DataInputStream): WorldMap {
        val world = Reader(input).value() as WorldMap
        if (input.readInt() != END_MARK) throw java.io.IOException("the world file has no end mark")
        return world
    }

    private class Writer(private val output: DataOutputStream) {
        /** Every container written so far, by identity, numbered in the order it was first met. */
        private val written = IdentityHashMap<Any, Int>()
        private val classNumbers = HashMap<String, Int>()

        fun value(value: Any?, path: String) {
            when {
                value == null -> output.writeByte(NULL)
                value is String -> {
                    output.writeByte(STRING)
                    writeString(value)
                }
                value is Int -> { output.writeByte(INT); output.writeInt(value) }
                value is Long -> { output.writeByte(LONG); output.writeLong(value) }
                value is Float -> { output.writeByte(FLOAT); output.writeInt(value.toRawBits()) }
                value is Double -> { output.writeByte(DOUBLE); output.writeLong(value.toRawBits()) }
                value is Short -> { output.writeByte(SHORT); output.writeShort(value.toInt()) }
                value is Byte -> { output.writeByte(BYTE); output.writeByte(value.toInt()) }
                value is Boolean -> { output.writeByte(BOOLEAN); output.writeBoolean(value) }
                value is Char -> { output.writeByte(CHAR); output.writeChar(value.code) }
                value is Unit -> output.writeByte(UNIT)
                value is Enum<*> -> {
                    output.writeByte(ENUM)
                    writeClass(value.declaringJavaClass)
                    output.writeInt(value.ordinal)
                }
                value is Class<*> -> {
                    output.writeByte(CLASS)
                    writeString(value.name)
                }
                ReachableState.isValue(value) -> error(
                    "cannot write a ${value.javaClass.name} at '$path': a value WorldFile has not " +
                        "been taught to write exactly"
                )
                else -> container(value, path)
            }
        }

        private fun container(value: Any, path: String) {
            val seenAs = written[value]
            if (seenAs != null) {
                output.writeByte(BACK_REFERENCE)
                output.writeInt(seenAs)
                return
            }
            singletonInstanceOf(value.javaClass)?.let { instance ->
                if (instance === value) {
                    output.writeByte(SINGLETON)
                    writeClass(value.javaClass)
                    return
                }
            }
            written[value] = written.size
            when (value) {
                is FloatArray -> primitives(FLOAT_ARRAY, value.size, 4) { it.asFloatBuffer().put(value) }
                is DoubleArray -> primitives(DOUBLE_ARRAY, value.size, 8) { it.asDoubleBuffer().put(value) }
                is IntArray -> primitives(INT_ARRAY, value.size, 4) { it.asIntBuffer().put(value) }
                is LongArray -> primitives(LONG_ARRAY, value.size, 8) { it.asLongBuffer().put(value) }
                is ShortArray -> primitives(SHORT_ARRAY, value.size, 2) { it.asShortBuffer().put(value) }
                is CharArray -> primitives(CHAR_ARRAY, value.size, 2) { it.asCharBuffer().put(value) }
                is ByteArray -> {
                    output.writeByte(BYTE_ARRAY)
                    output.writeInt(value.size)
                    output.write(value)
                }
                is BooleanArray -> {
                    output.writeByte(BOOLEAN_ARRAY)
                    output.writeInt(value.size)
                    output.write(ByteArray(value.size) { if (value[it]) 1 else 0 })
                }
                is Array<*> -> {
                    output.writeByte(OBJECT_ARRAY)
                    writeClass(value.javaClass.componentType)
                    output.writeInt(value.size)
                    val elementPath = "$path[]"
                    for (element in value) value(element, elementPath)
                }
                is Set<*> -> elements(SET, value, path)
                is Collection<*> -> elements(LIST, value, path)
                is Map<*, *> -> {
                    output.writeByte(MAP)
                    output.writeInt(value.size)
                    for ((key, element) in value) {
                        value(key, "$path{key}")
                        value(element, "$path[$key]")
                    }
                }
                is Lazy<*> -> {
                    output.writeByte(LAZY)
                    value(value.value, "$path.value")
                }
                else -> {
                    ReachableState.requireReadable(value.javaClass, path)
                    output.writeByte(OBJECT)
                    writeClass(value.javaClass)
                    for (field in ReachableState.instanceFieldsOf(value.javaClass)) {
                        value(field.get(value), "$path.${field.name}")
                    }
                }
            }
        }

        private fun elements(tag: Int, elements: Collection<*>, path: String) {
            output.writeByte(tag)
            output.writeInt(elements.size)
            // One path for every element, built once: an array of a biome per cell would otherwise
            // build millions of strings that only an error message reads.
            val elementPath = "$path[]"
            for (element in elements) value(element, elementPath)
        }

        /**
         * A primitive array as one block of bytes in the processor's own order, through a buffer
         * rather than element by element, which is what makes a 2,048-row world a few seconds to write
         * rather than a minute.
         */
        private inline fun primitives(tag: Int, size: Int, bytesEach: Int, fill: (ByteBuffer) -> Unit) {
            output.writeByte(tag)
            output.writeInt(size)
            val block = ByteBuffer.allocate(size * bytesEach).order(ByteOrder.LITTLE_ENDIAN)
            fill(block)
            output.write(block.array())
        }

        /** A class by number after its first appearance, since a river list names one class thousands of times. */
        private fun writeClass(type: Class<*>) {
            val number = classNumbers[type.name]
            if (number != null) {
                output.writeInt(number)
                return
            }
            output.writeInt(NEW_CLASS)
            writeString(type.name)
            classNumbers[type.name] = classNumbers.size
        }

        /** As a length and UTF-8 bytes, because `writeUTF` stops at 64 KB and a label need not. */
        private fun writeString(text: String) {
            val bytes = text.toByteArray(Charsets.UTF_8)
            output.writeInt(bytes.size)
            output.write(bytes)
        }
    }

    private class Reader(private val input: DataInputStream) {
        /** Every container read so far, numbered as the writer numbered them. */
        private val read = ArrayList<Any>()
        private val classes = ArrayList<Class<*>>()
        private val enumConstants = HashMap<Class<*>, Array<out Any>>()

        fun value(): Any? = when (val tag = input.readByte().toInt()) {
            NULL -> null
            STRING -> readString()
            INT -> input.readInt()
            LONG -> input.readLong()
            FLOAT -> Float.fromBits(input.readInt())
            DOUBLE -> Double.fromBits(input.readLong())
            SHORT -> input.readShort()
            BYTE -> input.readByte()
            BOOLEAN -> input.readBoolean()
            CHAR -> input.readChar()
            UNIT -> Unit
            ENUM -> constantsOf(readClass())[input.readInt()]
            CLASS -> Class.forName(readString(), false, loader)
            BACK_REFERENCE -> read[input.readInt()]
            SINGLETON -> {
                val type = readClass()
                singletonInstanceOf(type) ?: error("${type.name} is no longer a Kotlin object")
            }
            else -> container(tag)
        }

        private fun container(tag: Int): Any = when (tag) {
            FLOAT_ARRAY -> FloatArray(input.readInt()).also { remember(it); block(it.size, 4).asFloatBuffer().get(it) }
            DOUBLE_ARRAY -> DoubleArray(input.readInt()).also { remember(it); block(it.size, 8).asDoubleBuffer().get(it) }
            INT_ARRAY -> IntArray(input.readInt()).also { remember(it); block(it.size, 4).asIntBuffer().get(it) }
            LONG_ARRAY -> LongArray(input.readInt()).also { remember(it); block(it.size, 8).asLongBuffer().get(it) }
            SHORT_ARRAY -> ShortArray(input.readInt()).also { remember(it); block(it.size, 2).asShortBuffer().get(it) }
            CHAR_ARRAY -> CharArray(input.readInt()).also { remember(it); block(it.size, 2).asCharBuffer().get(it) }
            BYTE_ARRAY -> ByteArray(input.readInt()).also { remember(it); input.readFully(it) }
            BOOLEAN_ARRAY -> {
                val bytes = ByteArray(input.readInt())
                val booleans = BooleanArray(bytes.size)
                remember(booleans)
                input.readFully(bytes)
                for (index in bytes.indices) booleans[index] = bytes[index].toInt() != 0
                booleans
            }
            OBJECT_ARRAY -> {
                val componentType = readClass()
                @Suppress("UNCHECKED_CAST")
                val array = java.lang.reflect.Array.newInstance(componentType, input.readInt()) as Array<Any?>
                remember(array)
                for (index in array.indices) array[index] = value()
                array
            }
            SET -> {
                val size = input.readInt()
                LinkedHashSet<Any?>(size * 2).also { remember(it); repeat(size) { _ -> it.add(value()) } }
            }
            LIST -> {
                val size = input.readInt()
                ArrayList<Any?>(size).also { remember(it); repeat(size) { _ -> it.add(value()) } }
            }
            MAP -> {
                val size = input.readInt()
                LinkedHashMap<Any?, Any?>(size * 2).also { map ->
                    remember(map)
                    repeat(size) { map[value()] = value() }
                }
            }
            LAZY -> {
                // Numbered before its value is read, as the writer numbered it, but a lazy value can
                // only be made once that value is known; the slot is filled in afterwards.
                val slot = read.size
                read.add(PLACEHOLDER)
                lazyOf(value()).also { read[slot] = it }
            }
            OBJECT -> {
                val type = readClass()
                val instance = ReachableState.allocateWithoutConstructor(type)
                remember(instance)
                for (field in ReachableState.instanceFieldsOf(type)) field.set(instance, value())
                instance
            }
            else -> throw java.io.IOException("unknown tag $tag in a world file")
        }

        private fun remember(container: Any) {
            read.add(container)
        }

        private fun block(size: Int, bytesEach: Int): ByteBuffer {
            val bytes = ByteArray(size * bytesEach)
            input.readFully(bytes)
            return ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        }

        private fun readClass(): Class<*> {
            val number = input.readInt()
            if (number != NEW_CLASS) return classes[number]
            val type = Class.forName(readString(), false, loader)
            classes.add(type)
            return type
        }

        /** An enum's constants, asked for once: `enumConstants` copies the array on every call. */
        private fun constantsOf(type: Class<*>): Array<out Any> =
            enumConstants.getOrPut(type) { type.enumConstants }

        private fun readString(): String {
            val length = input.readInt()
            if (length < 0) throw EOFException("a negative string length in a world file")
            val bytes = ByteArray(length)
            input.readFully(bytes)
            return String(bytes, Charsets.UTF_8)
        }
    }

    /** The one instance of a Kotlin `object`, or null for any other class. */
    private fun singletonInstanceOf(type: Class<*>): Any? {
        val field = runCatching { type.getDeclaredField("INSTANCE") }.getOrNull() ?: return null
        if (!Modifier.isStatic(field.modifiers) || field.type != type) return null
        field.isAccessible = true
        return field.get(null)
    }

    private val loader: ClassLoader = WorldFile::class.java.classLoader

    private val PLACEHOLDER = Any()

    /** The last four bytes of every file, so a file cut short is refused rather than misread. */
    private const val END_MARK = 0x454E4421

    private const val NEW_CLASS = -1

    private const val NULL = 0
    private const val STRING = 1
    private const val INT = 2
    private const val LONG = 3
    private const val FLOAT = 4
    private const val DOUBLE = 5
    private const val SHORT = 6
    private const val BYTE = 7
    private const val BOOLEAN = 8
    private const val CHAR = 9
    private const val UNIT = 10
    private const val ENUM = 11
    private const val CLASS = 12
    private const val BACK_REFERENCE = 13
    private const val SINGLETON = 14
    private const val FLOAT_ARRAY = 20
    private const val DOUBLE_ARRAY = 21
    private const val INT_ARRAY = 22
    private const val LONG_ARRAY = 23
    private const val SHORT_ARRAY = 24
    private const val CHAR_ARRAY = 25
    private const val BYTE_ARRAY = 26
    private const val BOOLEAN_ARRAY = 27
    private const val OBJECT_ARRAY = 28
    private const val SET = 29
    private const val LIST = 30
    private const val MAP = 31
    private const val LAZY = 32
    private const val OBJECT = 33
}
