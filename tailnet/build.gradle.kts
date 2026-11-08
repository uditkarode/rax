import javax.inject.Inject

plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.serialization)
}

android {
    namespace = "de.uditkaro.rax.tailnet"
    compileSdk = 37
    ndkVersion = libs.versions.ndk.get()

    defaultConfig {
        minSdk = 29
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        consumerProguardFiles("consumer-rules.pro")
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    testOptions {
        unitTests.isReturnDefaultValues = true
    }
}

kotlin {
    jvmToolchain(21)
}

/** Runs build-go.sh, which builds the Go node into an AAR with gomobile. */
abstract class BuildGoAar : DefaultTask() {
    @get:InputFiles
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val goSources: ConfigurableFileCollection

    @get:InputFile
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val script: RegularFileProperty

    @get:Internal
    abstract val sdkDirectory: DirectoryProperty

    @get:Internal
    abstract val ndkDirectory: DirectoryProperty

    @get:Internal
    abstract val javaHome: DirectoryProperty

    @get:OutputFile
    abstract val aar: RegularFileProperty

    @get:Inject
    abstract val execOperations: ExecOperations

    @TaskAction
    fun build() {
        execOperations.exec {
            commandLine(script.get().asFile.absolutePath, aar.get().asFile.absolutePath)
            environment("ANDROID_HOME", sdkDirectory.get().asFile.absolutePath)
            environment("ANDROID_NDK_HOME", ndkDirectory.get().asFile.absolutePath)
            environment("JAVA_HOME", javaHome.get().asFile.absolutePath)
        }
    }
}

/** Unpacks the classes and native libraries of the gomobile AAR for this module. */
abstract class ExtractGoAar : DefaultTask() {
    @get:InputFile
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val aar: RegularFileProperty

    @get:OutputFile
    abstract val classesJar: RegularFileProperty

    @get:OutputDirectory
    abstract val jniLibs: DirectoryProperty

    @get:Inject
    abstract val archives: ArchiveOperations

    @get:Inject
    abstract val fileSystem: FileSystemOperations

    @TaskAction
    fun extract() {
        val contents = archives.zipTree(aar)
        fileSystem.copy {
            from(contents) { include("classes.jar") }
            into(classesJar.get().asFile.parentFile)
        }
        fileSystem.sync {
            from(contents) {
                include("jni/**")
                eachFile { relativePath = RelativePath(true, *relativePath.segments.drop(1).toTypedArray()) }
                includeEmptyDirs = false
            }
            into(jniLibs)
        }
    }
}

val buildGoAar = tasks.register<BuildGoAar>("buildGoAar") {
    goSources.from(fileTree("go") { include("**/*.go", "go.mod", "go.sum") })
    script = layout.projectDirectory.file("build-go.sh")
    sdkDirectory = androidComponents.sdkComponents.sdkDirectory
    ndkDirectory = androidComponents.sdkComponents.ndkDirectory
    javaHome = javaToolchains.launcherFor { languageVersion = JavaLanguageVersion.of(21) }
        .map { it.metadata.installationPath }
    aar = layout.projectDirectory.file("libs/tailnet-go.aar")
}

val extractGoAar = tasks.register<ExtractGoAar>("extractGoAar") {
    aar = buildGoAar.flatMap { it.aar }
    classesJar = layout.buildDirectory.file("go-aar/classes/classes.jar")
    jniLibs = layout.buildDirectory.dir("go-aar/jni")
}

androidComponents {
    onVariants { variant ->
        variant.sources.jniLibs?.addGeneratedSourceDirectory(extractGoAar, ExtractGoAar::jniLibs)
    }
}

dependencies {
    implementation(files(extractGoAar.flatMap { it.classesJar }))

    api(libs.kotlinx.coroutines.core)
    api(libs.okhttp)
    implementation(libs.kotlinx.serialization.json)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    androidTestImplementation(libs.androidx.test.junit)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.okhttp.mockwebserver)
}
