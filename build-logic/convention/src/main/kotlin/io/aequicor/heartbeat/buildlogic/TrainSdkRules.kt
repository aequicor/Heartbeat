package io.aequicor.heartbeat.buildlogic

import org.gradle.api.DefaultTask
import org.gradle.api.Project
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.tasks.CacheableTask
import org.gradle.api.tasks.Classpath
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.TaskAction
import org.gradle.jvm.tasks.Jar
import java.io.File
import java.util.zip.ZipFile
import kotlin.metadata.ClassKind
import kotlin.metadata.KmClass
import kotlin.metadata.KmConstructor
import kotlin.metadata.KmFunction
import kotlin.metadata.KmPackage
import kotlin.metadata.KmProperty
import kotlin.metadata.Visibility
import kotlin.metadata.jvm.KotlinClassMetadata
import kotlin.metadata.jvm.fieldSignature
import kotlin.metadata.jvm.getterSignature
import kotlin.metadata.jvm.setterSignature
import kotlin.metadata.jvm.signature
import kotlin.metadata.jvm.syntheticMethodForAnnotations
import kotlin.metadata.kind
import kotlin.metadata.visibility

private const val KIND_CLASS = 1
private const val KIND_FILE_FACADE = 2
private const val KIND_SYNTHETIC_CLASS = 3
private const val KIND_MULTI_FILE_FACADE = 4
private const val KIND_MULTI_FILE_PART = 5
private const val DEFAULT_SUFFIX = "\$default"
private const val DEFAULT_IMPLS = "\$DefaultImpls"

/**
 * `-keep` rules that preserve the public API of the SDK modules of the train by name: the classes reachable from
 * other jars and their members, as Kotlin sees them.
 *
 * Kotlin metadata decides what is public: `public`/`protected` declarations stay, `internal` and `private` ones are
 * free to be shrunk and renamed, except `@PublishedApi` ones (inline functions of other jars call them). Compiled
 * Kotlin makes `internal` members public in the bytecode, so the access flags alone would keep them. Classes without
 * Kotlin metadata (Java) are judged by their access flags. The result is sorted: equal [classFiles] give equal rules.
 */
internal fun sdkKeepRules(classFiles: Sequence<ByteArray>): String {
    val rules = classFiles.map(::readClassFile)
        .filter { it.name != "module-info" && !it.name.endsWith("/package-info") }
        .mapNotNull(::keepRule)
        .sortedBy { it.className }
        .toList()
    return buildString {
        appendLine("# Generated from the train SDK modules (heartbeatTrain { sdk(...) }): their public API keeps its names.")
        for (rule in rules) {
            if (rule.members.isEmpty()) {
                appendLine("-keep,includedescriptorclasses class ${rule.className}")
            } else {
                appendLine("-keep,includedescriptorclasses class ${rule.className} {")
                rule.members.forEach { appendLine("    $it") }
                appendLine("}")
            }
        }
    }
}

private class KeepRule(val className: String, val members: List<String>)

/** What the Kotlin metadata declares non-public: JVM signatures (`name + descriptor`) and the published ones. */
private class Internals {
    val hidden = mutableSetOf<String>()
    val published = mutableSetOf<String>()
    var constructorsKept = true
}

private fun keepRule(info: ClassFileInfo): KeepRule? {
    val metadata = info.metadata?.let { raw ->
        val annotation = Metadata(
            kind = raw.kind,
            metadataVersion = raw.version,
            data1 = raw.data1,
            data2 = raw.data2,
            extraString = raw.extraString,
            packageName = raw.packageName,
            extraInt = raw.extraInt,
        )
        KotlinClassMetadata.readStrict(annotation)
    }
    val internals = Internals()
    val whole: Boolean
    when (metadata) {
        null -> whole = info.isReachable
        is KotlinClassMetadata.Class -> {
            whole = metadata.kmClass.isKept(info)
            collect(metadata.kmClass, info, internals)
        }
        is KotlinClassMetadata.FileFacade -> {
            whole = false
            collect(metadata.kmPackage, info, internals)
        }
        is KotlinClassMetadata.MultiFileClassPart -> {
            whole = false
            collect(metadata.kmPackage, info, internals)
        }
        is KotlinClassMetadata.MultiFileClassFacade -> whole = true
        is KotlinClassMetadata.SyntheticClass -> whole = info.name.endsWith(DEFAULT_IMPLS)
        else -> whole = info.isReachable
    }
    val isFacade = metadata is KotlinClassMetadata.FileFacade || metadata is KotlinClassMetadata.MultiFileClassPart
    if (!whole && !isFacade) return null

    val hiddenNames = internals.hidden.map { it.substringBefore('(') }.toSet()
    val methods = info.methods.filter { method ->
        method.isReachable && (method.name + method.descriptor) !in internals.hidden &&
            !(method.name.endsWith(DEFAULT_SUFFIX) && method.name.removeSuffix(DEFAULT_SUFFIX) in hiddenNames)
    }.filter { it.name != "<init>" || internals.constructorsKept }
        .sortedWith(compareBy({ it.name }, { it.descriptor }))
        .map { "*** ${it.name}(${descriptorParameters(it.descriptor)});".replace("*** <init>", "<init>") }
    val fields = info.fields.filter { it.isReachable && (it.name + ":" + it.descriptor) !in internals.hidden }
        .sortedBy { it.name }
        .map { "*** ${it.name};" }
    val members = (fields + methods).distinct()
    // A file facade whose declarations are all internal has nothing to keep.
    if (!whole && members.isEmpty()) return null
    return KeepRule(info.name.replace('/', '.'), members)
}

private fun KmClass.isKept(info: ClassFileInfo): Boolean = when (visibility) {
    Visibility.PUBLIC, Visibility.PROTECTED -> true
    Visibility.INTERNAL -> info.isPublishedApi
    else -> false
}

private fun collect(kmClass: KmClass, info: ClassFileInfo, internals: Internals) {
    val published = publishedSignatures(info)
    kmClass.functions.forEach { hide(it, published, internals) }
    kmClass.properties.forEach { hide(it, published, internals) }
    val constructors = kmClass.constructors
    val visible = constructors.filter { it.isReachable(published) }
    constructors.filterNot { it.isReachable(published) }.forEach { constructor ->
        constructor.signature?.let { internals.hidden += it.name + it.descriptor }
    }
    // Kotlin classes without reachable constructors (objects, internal ones) are never instantiated from outside.
    internals.constructorsKept = visible.isNotEmpty() || constructors.isEmpty()
    if (kmClass.kind == ClassKind.OBJECT || kmClass.kind == ClassKind.COMPANION_OBJECT) internals.constructorsKept = false
}

private fun collect(kmPackage: KmPackage, info: ClassFileInfo, internals: Internals) {
    val published = publishedSignatures(info)
    kmPackage.functions.forEach { hide(it, published, internals) }
    kmPackage.properties.forEach { hide(it, published, internals) }
}

/** `name + descriptor` of the methods and `name:descriptor` of the fields that carry `@PublishedApi`. */
private fun publishedSignatures(info: ClassFileInfo): Set<String> =
    info.methods.filter { it.isPublishedApi }.map { it.name + it.descriptor }.toSet() +
        info.fields.filter { it.isPublishedApi }.map { it.name + ":" + it.descriptor }

private fun hide(function: KmFunction, published: Set<String>, internals: Internals) {
    val signature = function.signature ?: return
    val key = signature.name + signature.descriptor
    if (function.visibility.isReachable() || key in published) return
    internals.hidden += key
}

private fun hide(property: KmProperty, published: Set<String>, internals: Internals) {
    val signatures = listOfNotNull(
        property.getterSignature?.let { it.name + it.descriptor },
        property.setterSignature?.let { it.name + it.descriptor },
        property.fieldSignature?.let { it.name + ":" + it.descriptor },
    )
    val annotated = property.syntheticMethodForAnnotations?.let { it.name + it.descriptor }
    if (property.visibility.isReachable() || annotated in published || signatures.any { it in published }) return
    internals.hidden += signatures
}

private fun KmConstructor.isReachable(published: Set<String>): Boolean =
    visibility.isReachable() || signature?.let { it.name + it.descriptor in published } == true

private fun Visibility.isReachable(): Boolean = this == Visibility.PUBLIC || this == Visibility.PROTECTED

/** ProGuard parameter list (`int,java.lang.String[]`) of a JVM method [descriptor]. */
internal fun descriptorParameters(descriptor: String): String {
    val parameters = mutableListOf<String>()
    var index = descriptor.indexOf('(') + 1
    while (descriptor[index] != ')') {
        var dimensions = 0
        while (descriptor[index] == '[') {
            dimensions++
            index++
        }
        val type = when (val code = descriptor[index]) {
            'L' -> {
                val end = descriptor.indexOf(';', index)
                descriptor.substring(index + 1, end).replace('/', '.').also { index = end }
            }
            else -> primitiveName(code)
        }
        index++
        parameters += type + "[]".repeat(dimensions)
    }
    return parameters.joinToString(",")
}

/**
 * Task `generateTrainSdkRules`: writes the keep rules of [sdkKeepRules] for the class files of the SDK modules
 * (jars or class directories in [classes]).
 */
@CacheableTask
abstract class GenerateTrainSdkRules : DefaultTask() {
    @get:Classpath
    abstract val classes: ConfigurableFileCollection

    @get:OutputFile
    abstract val rules: RegularFileProperty

    @TaskAction
    fun generate() {
        val text = sdkKeepRules(classes.files.sortedBy { it.path }.asSequence().flatMap(::classFiles))
        rules.get().asFile.apply { parentFile.mkdirs() }.writeText(text)
    }
}

private fun classFiles(root: File): Sequence<ByteArray> = when {
    root.isDirectory -> root.walkTopDown().filter { it.isFile && it.extension == "class" }.map { it.readBytes() }
    root.isFile -> sequence {
        ZipFile(root).use { zip ->
            for (entry in zip.entries().asSequence().filter { it.name.endsWith(".class") && !it.name.startsWith("META-INF/") }) {
                yield(zip.getInputStream(entry).use { it.readBytes() })
            }
        }
    }
    else -> emptySequence()
}

/** Registers the root `generateTrainSdkRules` task over the jars of the SDK modules of [extension]. */
internal fun Project.registerGenerateTrainSdkRules(extension: TrainSdkExtension) {
    val root = rootProject
    root.tasks.register(GENERATE_TRAIN_SDK_RULES_TASK, GenerateTrainSdkRules::class.java) {
        group = "build"
        description = "Generates the ProGuard keep rules of the public API of the train SDK modules."
        classes.from(
            root.provider {
                extension.sdk.get().sorted().mapNotNull { root.findProject(it) }.flatMap { module ->
                    module.tasks.withType(Jar::class.java).matching { it.name == "jar" || it.name == "jvmJar" }
                        .map { it.outputs.files }
                }
            },
        )
        rules.set(root.layout.buildDirectory.file("train/sdk-rules.pro"))
    }
}

internal const val GENERATE_TRAIN_SDK_RULES_TASK = "generateTrainSdkRules"

private fun primitiveName(code: Char): String = when (code) {
    'B' -> "byte"
    'C' -> "char"
    'D' -> "double"
    'F' -> "float"
    'I' -> "int"
    'J' -> "long"
    'S' -> "short"
    'Z' -> "boolean"
    else -> error("Unknown descriptor type $code")
}
