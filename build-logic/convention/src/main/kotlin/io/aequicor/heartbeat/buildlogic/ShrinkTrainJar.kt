package io.aequicor.heartbeat.buildlogic

import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.Project
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.Property
import org.gradle.api.provider.Provider
import org.gradle.api.tasks.Classpath
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.InputFiles
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import org.gradle.process.ExecOperations
import java.io.File
import java.security.MessageDigest
import java.time.LocalDateTime
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream
import javax.inject.Inject

/** How much the artifacts of the train are processed: `-Pheartbeat.trainShrink=off|shrink|obfuscate`, `off` by default. */
internal enum class TrainShrinkMode(val id: String) {
    /** The jars are used as assembled. */
    Off("off"),

    /** Unused classes and members are removed; nothing is renamed or optimized. */
    Shrink("shrink"),

    /**
     * [Shrink] and the names of the internals are shortened: classes and members that no keep rule names. Packages stay,
     * and every artifact draws its class names from a dictionary of its own, so jars loaded by one class loader never
     * meet the same class name. Nothing is optimized.
     */
    Obfuscate("obfuscate"),
    ;

    companion object {
        const val PROPERTY = "heartbeat.trainShrink"

        fun parse(value: String?): TrainShrinkMode = when (value?.trim()?.lowercase()) {
            null, "", Off.id -> Off
            Shrink.id -> Shrink
            Obfuscate.id -> Obfuscate
            else -> throw GradleException("-P$PROPERTY must be one of ${entries.joinToString("|") { it.id }}, got '$value'")
        }
    }
}

/** Directory of the assembled jars that still wait for processing, next to the final `bundle` directory. */
internal const val RAW_JAR_DIRECTORY = "bundle-raw"

/** Directory the jars of the train are read from by the manifest, the seed and the publication. */
internal const val FINAL_JAR_DIRECTORY = "bundle"

/**
 * ProGuard configuration of one artifact of the train.
 *
 * [input] is processed into [output]. [keptInputs] are program jars that are read in the same run so that what they
 * reference stays reachable, but that are kept whole and written under [scratchDirectory] only to satisfy ProGuard
 * (the contracts jar is used as it is, see [keptPackages]). [libraryJars] and the [jmods] of the JDK are not changed.
 * Warnings are never silenced: a reference that cannot be resolved fails the run. With a [classDictionary] the run
 * obfuscates: class names come from it, packages keep their names (see [TrainShrinkMode.Obfuscate]).
 */
internal fun trainProguardConfiguration(
    input: File,
    output: File,
    keptInputs: List<File>,
    scratchDirectory: File,
    libraryJars: List<File>,
    jmods: List<File>,
    rules: List<File>,
    keptPackages: Collection<String>,
    mapping: File,
    classDictionary: File? = null,
): List<String> = buildList {
    add("-injars ${quoted(input)}")
    add("-outjars ${quoted(output)}")
    keptInputs.forEach { kept ->
        add("-injars ${quoted(kept)}")
        add("-outjars ${quoted(File(scratchDirectory, kept.name))}")
    }
    jmods.forEach { add("-libraryjars ${quoted(it)}(!**.jar;!module-info.class)") }
    libraryJars.forEach { add("-libraryjars ${quoted(it)}") }
    if (classDictionary == null) {
        add("-dontobfuscate")
    } else {
        add("-classobfuscationdictionary ${quoted(classDictionary)}")
        add("-keeppackagenames **")
        add("-dontusemixedcaseclassnames")
    }
    add("-dontoptimize")
    // Notes only (duplicate library classes, rules naming library classes): warnings stay fatal.
    add("-dontnote")
    add("-keepattributes *")
    add("-printmapping ${quoted(mapping)}")
    rules.forEach { add("-include ${quoted(it)}") }
    keptPackages.sorted().forEach { add("-keep,includedescriptorclasses class $it.* { *; }") }
}

/**
 * Short, stable prefix of the obfuscated class names of the artifact [id] (`k3q` for `web`): a letter and two letters or
 * digits taken from the SHA-256 of the id. Artifacts with different tags never get the same class name, however their
 * packages overlap.
 */
internal fun obfuscationTag(id: String): String {
    val digest = MessageDigest.getInstance("SHA-256").digest(id.toByteArray())
    val letters = ('a'..'z').toList()
    val alphabet = letters + ('0'..'9')
    return buildString {
        append(letters[(digest[0].toInt() and 0xFF) % letters.size])
        append(alphabet[(digest[1].toInt() and 0xFF) % alphabet.size])
        append(alphabet[(digest[2].toInt() and 0xFF) % alphabet.size])
    }
}

/** The names `<tag>0`, `<tag>1`, … `<tag>z`, `<tag>10`, …: [count] class names that ProGuard hands out in order. */
internal fun classDictionary(tag: String, count: Int): String =
    (0 until count).joinToString("\n", postfix = "\n") { tag + it.toString(CLASS_NAME_RADIX) }

private const val CLASS_NAME_RADIX = 36
private const val CLASS_DICTIONARY_SIZE = 20_000

private fun quoted(file: File): String = "\"${file.absolutePath}\""

/**
 * `jmods` of the JDK that are platform classes of the app. The JetBrains Runtime carries its own JCEF, JOGL and GlueGen
 * modules, but the app runs on the pinned API/native pair of `heartbeat.jcef` ahead of them, so they must not shadow it.
 */
internal fun platformJmods(directory: File): List<File> =
    directory.listFiles { file -> file.extension == "jmod" && file.nameWithoutExtension !in SHADOWED_JMODS }.orEmpty().sortedBy { it.name }

private val SHADOWED_JMODS = setOf("jcef", "jogl.all", "gluegen.rt")

/** Packages (`a.b`) of the classes in [jar], sorted: what ProGuard must keep whole to leave the jar as it is. */
internal fun jarPackages(jar: File): List<String> = ZipFile(jar).use { zip ->
    zip.entries().asSequence()
        .map { it.name }
        .filter { it.endsWith(".class") && !it.startsWith("META-INF/") && it != "module-info.class" }
        .map { it.substringBeforeLast('/', "").replace('/', '.') }
        .toSortedSet()
        .toList()
}

/**
 * Runs ProGuard over one jar of the train. The jar and the processed one have the same file name, so the manifest
 * and the installer do not depend on whether the train was processed. Equal inputs give equal bytes.
 */
abstract class ShrinkTrainJar @Inject constructor(private val exec: ExecOperations) : DefaultTask() {
    /** The assembled jar. */
    @get:InputFile
    @get:PathSensitive(PathSensitivity.NAME_ONLY)
    abstract val input: RegularFileProperty

    /** Jars read together with [input] and kept whole (the contracts the artifact is built against). */
    @get:InputFiles
    @get:PathSensitive(PathSensitivity.NAME_ONLY)
    abstract val keptJars: ConfigurableFileCollection

    /** Jars the artifact is compiled against and that are not changed by this run. */
    @get:InputFiles
    @get:PathSensitive(PathSensitivity.NAME_ONLY)
    abstract val libraryJars: ConfigurableFileCollection

    /** ProGuard rule files: the generated keep rules of the SDK and the train's own ones. */
    @get:InputFiles
    @get:PathSensitive(PathSensitivity.NAME_ONLY)
    abstract val rules: ConfigurableFileCollection

    /** The id of the artifact (`foundation`, a bundle id): its class names are drawn from a dictionary of its own. */
    @get:Input
    abstract val artifactId: Property<String>

    /** Whether the names are shortened ([TrainShrinkMode.Obfuscate]) or only unused code is removed. */
    @get:Input
    abstract val obfuscate: Property<Boolean>

    /** Home of the JDK whose `jmods` are the platform classes. */
    @get:Input
    abstract val javaHome: Property<String>

    @get:Classpath
    abstract val proguardClasspath: ConfigurableFileCollection

    @get:OutputFile
    abstract val output: RegularFileProperty

    @get:OutputFile
    abstract val mapping: RegularFileProperty

    @TaskAction
    fun shrink() {
        val outputFile = output.get().asFile.apply { parentFile.mkdirs() }
        val mappingFile = mapping.get().asFile.apply { parentFile.mkdirs() }
        val scratch = File(temporaryDir, "scratch").apply {
            deleteRecursively()
            mkdirs()
        }
        val jmodsDirectory = File(javaHome.get(), "jmods")
        if (!jmodsDirectory.isDirectory) {
            throw GradleException("The JDK ${javaHome.get()} has no jmods directory: ProGuard needs the platform classes")
        }
        val kept = keptJars.files.sortedBy { it.name }
        val processed = File(temporaryDir, "processed.jar")
        val dictionary = File(temporaryDir, "classes.dict").takeIf { obfuscate.get() }?.apply {
            writeText(classDictionary(obfuscationTag(artifactId.get()), CLASS_DICTIONARY_SIZE))
        }
        val configuration = trainProguardConfiguration(
            input = input.get().asFile,
            output = processed,
            keptInputs = kept,
            scratchDirectory = scratch,
            libraryJars = libraryJars.files.sortedBy { it.name }.filter { it != input.get().asFile },
            jmods = platformJmods(jmodsDirectory),
            rules = rules.files.sortedBy { it.name },
            keptPackages = kept.flatMap(::jarPackages),
            mapping = mappingFile,
            classDictionary = dictionary,
        )
        val configurationFile = File(temporaryDir, "proguard.pro").apply { writeText(configuration.joinToString("\n", postfix = "\n")) }
        exec.javaexec {
            classpath(proguardClasspath)
            mainClass.set("proguard.ProGuard")
            args("@${configurationFile.absolutePath}")
            maxHeapSize = "2g"
        }
        normalizeJar(processed, outputFile)
    }
}

/**
 * Copies [source] to [target] with every entry stamped with the same fixed time: ProGuard stamps resources and the
 * manifest with the time of the run, which would give every build a new SHA-256.
 */
internal fun normalizeJar(source: File, target: File) {
    ZipFile(source).use { zip ->
        ZipOutputStream(target.outputStream().buffered()).use { out ->
            for (entry in zip.entries().asSequence()) {
                out.putNextEntry(ZipEntry(entry.name).apply { setTimeLocal(FIXED_ENTRY_TIME) })
                if (!entry.isDirectory) zip.getInputStream(entry).use { it.copyTo(out) }
                out.closeEntry()
            }
        }
    }
}

/** 1980-02-01 as a local time, so that the bytes do not depend on the time zone (like Gradle's reproducible jars). */
private val FIXED_ENTRY_TIME: LocalDateTime = LocalDateTime.of(1980, 2, 1, 0, 0)

/** The `trainProguard` configuration holding ProGuard itself (the version of the catalog). */
internal fun Project.trainProguardClasspath(): ConfigurableFileCollection {
    val configuration = configurations.maybeCreate("trainProguard").apply {
        isCanBeConsumed = false
        isCanBeResolved = true
    }
    if (configuration.dependencies.isEmpty()) {
        val catalog = extensions.getByType(org.gradle.api.artifacts.VersionCatalogsExtension::class.java).named("libs")
        dependencies.add(configuration.name, catalog.findLibrary("proguard-base").get())
    }
    return files(configuration)
}

/** Home of the JetBrains Runtime of the desktop app (`desktop-jdk` in the catalog), whose `jmods` ProGuard reads. */
internal fun Project.desktopRuntimeHome(): Provider<String> {
    val catalog = extensions.getByType(org.gradle.api.artifacts.VersionCatalogsExtension::class.java).named("libs")
    val version = catalog.findVersion("desktop-jdk").get().requiredVersion.toInt()
    return extensions.getByType(org.gradle.jvm.toolchain.JavaToolchainService::class.java).launcherFor {
        languageVersion.set(org.gradle.jvm.toolchain.JavaLanguageVersion.of(version))
        vendor.set(org.gradle.jvm.toolchain.JvmVendorSpec.JETBRAINS)
    }.map { it.metadata.installationPath.asFile.absolutePath }
}

/** Rule files of the train that exist: the generated SDK rules and the hand-written `train-rules.pro`. */
internal fun Project.trainRuleFiles(): List<Any> {
    val root = rootProject
    // Bundles may be configured before the desktop module registers the generator: take the task lazily.
    return listOf(
        root.tasks.withType(GenerateTrainSdkRules::class.java),
        root.provider { listOfNotNull(root.file(TRAIN_RULES_PATH).takeIf(File::isFile)) },
    )
}

internal const val TRAIN_RULES_PATH = "platform-main/desktop/train-rules.pro"

/** Registers `shrink<Name>` for [input], writing the processed jar to the final jar directory. */
internal fun Project.registerShrinkTrainJar(
    taskName: String,
    input: Provider<File>,
    jarName: Provider<String>,
    artifactId: String,
    mode: TrainShrinkMode,
    configure: ShrinkTrainJar.() -> Unit,
) = tasks.register(taskName, ShrinkTrainJar::class.java) {
    group = "build"
    description = "Processes the assembled jar of the bundle train with ProGuard (-P${TrainShrinkMode.PROPERTY}=${mode.id})."
    this.artifactId.set(artifactId)
    obfuscate.set(mode == TrainShrinkMode.Obfuscate)
    this.input.fileProvider(input)
    output.set(layout.buildDirectory.file(jarName.map { "$FINAL_JAR_DIRECTORY/$it" }))
    mapping.set(layout.buildDirectory.file(jarName.map { "train/mapping-${it.removeSuffix(".jar")}.txt" }))
    javaHome.set(desktopRuntimeHome())
    proguardClasspath.from(trainProguardClasspath())
    trainRuleFiles().forEach { rules.from(it) }
    configure()
}
