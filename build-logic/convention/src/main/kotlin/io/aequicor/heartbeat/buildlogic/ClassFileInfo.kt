package io.aequicor.heartbeat.buildlogic

import java.io.ByteArrayInputStream
import java.io.DataInputStream

/** A field or a method of a class file: JVM [name], [descriptor], access [flags] and annotation descriptors. */
internal class MemberInfo(
    val name: String,
    val descriptor: String,
    val flags: Int,
    val annotations: Set<String>,
) {
    val isReachable: Boolean get() = flags and (ACC_PUBLIC or ACC_PROTECTED) != 0
    val isPublishedApi: Boolean get() = PUBLISHED_API in annotations
}

/** The raw `kotlin.Metadata` annotation of a class file. */
internal class RawKotlinMetadata(
    val kind: Int,
    val version: IntArray,
    val data1: Array<String>,
    val data2: Array<String>,
    val extraString: String,
    val packageName: String,
    val extraInt: Int,
)

/**
 * What the generator of the SDK keep rules needs from a class file: names, access flags, annotations,
 * the `kotlin.Metadata` payload. [flags] of a nested class are the ones of its `InnerClasses` entry, which tell
 * whether it is reachable from other jars.
 */
internal class ClassFileInfo(
    val name: String,
    val flags: Int,
    val annotations: Set<String>,
    val fields: List<MemberInfo>,
    val methods: List<MemberInfo>,
    val metadata: RawKotlinMetadata?,
) {
    val isReachable: Boolean get() = flags and (ACC_PUBLIC or ACC_PROTECTED) != 0
    val isPublishedApi: Boolean get() = PUBLISHED_API in annotations
}

internal const val ACC_PUBLIC = 0x0001
internal const val ACC_PROTECTED = 0x0004
internal const val ACC_STATIC = 0x0008
internal const val ACC_SYNTHETIC = 0x1000
internal const val PUBLISHED_API = "Lkotlin/PublishedApi;"
private const val METADATA = "Lkotlin/Metadata;"
private const val CLASS_MAGIC = 0xCAFEBABE.toInt()

private const val TAG_UTF8 = 1
private const val TAG_INTEGER = 3
private const val TAG_FLOAT = 4
private const val TAG_LONG = 5
private const val TAG_DOUBLE = 6
private const val TAG_CLASS = 7
private const val TAG_STRING = 8
private const val TAG_FIELD_REF = 9
private const val TAG_METHOD_REF = 10
private const val TAG_INTERFACE_METHOD_REF = 11
private const val TAG_NAME_AND_TYPE = 12
private const val TAG_METHOD_HANDLE = 15
private const val TAG_METHOD_TYPE = 16
private const val TAG_DYNAMIC = 17
private const val TAG_INVOKE_DYNAMIC = 18
private const val TAG_MODULE = 19
private const val TAG_PACKAGE = 20

/** Reads [bytes] of a class file; only the pieces the keep rules depend on are interpreted. */
internal fun readClassFile(bytes: ByteArray): ClassFileInfo {
    val input = DataInputStream(ByteArrayInputStream(bytes))
    require(input.readInt() == CLASS_MAGIC) { "Not a class file" }
    input.readUnsignedShort()
    input.readUnsignedShort()
    val pool = readConstantPool(input)
    var flags = input.readUnsignedShort()
    val name = pool.className(input.readUnsignedShort())
    input.readUnsignedShort()
    repeat(input.readUnsignedShort()) { input.readUnsignedShort() }
    val fields = readMembers(input, pool)
    val methods = readMembers(input, pool)

    val annotations = mutableSetOf<String>()
    var metadata: RawKotlinMetadata? = null
    repeat(input.readUnsignedShort()) {
        val attribute = pool.utf8(input.readUnsignedShort())
        val length = input.readInt()
        when (attribute) {
            "RuntimeVisibleAnnotations", "RuntimeInvisibleAnnotations" -> {
                val found = readAnnotations(input, pool)
                annotations += found.map { it.type }
                found.firstOrNull { it.type == METADATA }?.let { metadata = it.toMetadata() }
            }
            "InnerClasses" -> repeat(input.readUnsignedShort()) {
                val inner = input.readUnsignedShort()
                input.readUnsignedShort()
                input.readUnsignedShort()
                val innerFlags = input.readUnsignedShort()
                if (inner != 0 && pool.className(inner) == name) flags = innerFlags
            }
            else -> input.skipFully(length)
        }
    }
    return ClassFileInfo(name, flags, annotations, fields, methods, metadata)
}

private class ConstantPool(private val entries: Array<Any?>) {
    fun utf8(index: Int): String = entries[index] as String

    /** The binary name of the class constant at [index]. */
    fun className(index: Int): String = utf8(entries[index] as Int)

    fun int(index: Int): Int? = entries[index] as? Int
}

private fun readConstantPool(input: DataInputStream): ConstantPool {
    val entries = arrayOfNulls<Any?>(input.readUnsignedShort())
    var index = 1
    while (index < entries.size) {
        when (val tag = input.readUnsignedByte()) {
            TAG_UTF8 -> entries[index] = input.readUTF()
            TAG_INTEGER -> entries[index] = input.readInt()
            TAG_FLOAT -> input.readInt()
            TAG_LONG, TAG_DOUBLE -> {
                input.readLong()
                index++
            }
            TAG_CLASS, TAG_STRING, TAG_METHOD_TYPE, TAG_MODULE, TAG_PACKAGE -> entries[index] = input.readUnsignedShort()
            TAG_FIELD_REF, TAG_METHOD_REF, TAG_INTERFACE_METHOD_REF, TAG_NAME_AND_TYPE, TAG_DYNAMIC, TAG_INVOKE_DYNAMIC ->
                input.readInt()
            TAG_METHOD_HANDLE -> {
                input.readUnsignedByte()
                input.readUnsignedShort()
            }
            else -> error("Unknown constant pool tag $tag")
        }
        index++
    }
    return ConstantPool(entries)
}

private fun readMembers(input: DataInputStream, pool: ConstantPool): List<MemberInfo> =
    List(input.readUnsignedShort()) {
        val flags = input.readUnsignedShort()
        val name = pool.utf8(input.readUnsignedShort())
        val descriptor = pool.utf8(input.readUnsignedShort())
        val annotations = mutableSetOf<String>()
        repeat(input.readUnsignedShort()) {
            val attribute = pool.utf8(input.readUnsignedShort())
            val length = input.readInt()
            if (attribute == "RuntimeVisibleAnnotations" || attribute == "RuntimeInvisibleAnnotations") {
                annotations += readAnnotations(input, pool).map { it.type }
            } else {
                input.skipFully(length)
            }
        }
        MemberInfo(name, descriptor, flags, annotations)
    }

private class RawAnnotation(val type: String, val values: Map<String, Any>) {
    @Suppress("UNCHECKED_CAST") // The element shapes are fixed by the `kotlin.Metadata` declaration.
    fun toMetadata() = RawKotlinMetadata(
        kind = values["k"] as? Int ?: 1,
        version = (values["mv"] as? List<Int>).orEmpty().toIntArray(),
        data1 = (values["d1"] as? List<String>).orEmpty().toTypedArray(),
        data2 = (values["d2"] as? List<String>).orEmpty().toTypedArray(),
        extraString = values["xs"] as? String ?: "",
        packageName = values["pn"] as? String ?: "",
        extraInt = values["xi"] as? Int ?: 0,
    )
}

private fun readAnnotations(input: DataInputStream, pool: ConstantPool): List<RawAnnotation> =
    List(input.readUnsignedShort()) { readAnnotation(input, pool) }

private fun readAnnotation(input: DataInputStream, pool: ConstantPool): RawAnnotation {
    val type = pool.utf8(input.readUnsignedShort())
    val values = buildMap {
        repeat(input.readUnsignedShort()) {
            val key = pool.utf8(input.readUnsignedShort())
            readElementValue(input, pool)?.let { put(key, it) }
        }
    }
    return RawAnnotation(type, values)
}

/** Integer and string constants and arrays of them; any other element is skipped and read as `null`. */
private fun readElementValue(input: DataInputStream, pool: ConstantPool): Any? =
    when (val tag = input.readUnsignedByte().toChar()) {
        'I', 'B', 'C', 'S', 'Z' -> pool.int(input.readUnsignedShort())
        's' -> pool.utf8(input.readUnsignedShort())
        'J', 'F', 'D', 'c' -> {
            input.readUnsignedShort()
            null
        }
        'e' -> {
            input.readInt()
            null
        }
        '@' -> {
            readAnnotation(input, pool)
            null
        }
        '[' -> List(input.readUnsignedShort()) { readElementValue(input, pool) }.filterNotNull()
        else -> error("Unknown annotation element tag $tag")
    }

private fun DataInputStream.skipFully(count: Int) {
    var left = count
    while (left > 0) {
        val skipped = skipBytes(left)
        check(skipped > 0) { "Truncated class file" }
        left -= skipped
    }
}
