import javax.inject.Inject

plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "io.github.xchacha20_poly1305.libghostty"
    compileSdk = 37
    ndkVersion = libs.versions.ndk.get()

    defaultConfig {
        minSdk = 29
        consumerProguardFiles("consumer-rules.pro")
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
    }

    testOptions {
        unitTests.isReturnDefaultValues = true
    }
}

kotlin {
    jvmToolchain(21)
    explicitApi()
}

/** Runs one command of build-native.sh, which builds the JNI bridge and its Kotlin mirrors with zig. */
abstract class BuildGhosttyNative : DefaultTask() {
    @get:Input
    abstract val command: Property<String>

    @get:InputFiles
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val sources: ConfigurableFileCollection

    @get:InputFile
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val script: RegularFileProperty

    @get:Internal
    abstract val ndkDirectory: DirectoryProperty

    @get:Input
    @get:Optional
    abstract val ndkVersion: Property<String>

    @get:Input
    abstract val zigVersion: Property<String>

    @get:OutputDirectory
    abstract val outputDir: DirectoryProperty

    @get:Inject
    abstract val execOperations: ExecOperations

    @TaskAction
    fun build() {
        execOperations.exec {
            commandLine(script.get().asFile.absolutePath, command.get(), outputDir.get().asFile.absolutePath)
            if (ndkDirectory.isPresent) environment("ANDROID_NDK_HOME", ndkDirectory.get().asFile.absolutePath)
        }
    }
}

val buildScript = layout.projectDirectory.file("build-native.sh")
val pin = files("GHOSTTY_COMMIT", fileTree("patches") { include("*.patch") })
val nativeSources = fileTree("native") { exclude(".zig-cache/**", "zig-out/**", "zig-pkg/**") }
val installedZig = providers.exec { commandLine(buildScript.asFile.absolutePath, "zig-version") }
    .standardOutput.asText.map { it.trim() }

val ghosttySource = tasks.register<BuildGhosttyNative>("ghosttySource") {
    command = "source"
    sources.from(pin)
    script = buildScript
    zigVersion = ""
    outputDir = layout.buildDirectory.dir("ghostty-source")
}

fun registerZigBuild(name: String, command: String, output: String) = tasks.register<BuildGhosttyNative>(name) {
    dependsOn(ghosttySource)
    this.command = command
    sources.from(pin, nativeSources)
    script = buildScript
    zigVersion = installedZig
    outputDir = layout.buildDirectory.dir(output)
}

val ghosttyJni = registerZigBuild("ghosttyJni", "android", "ghostty/jniLibs").also {
    it.configure {
        ndkDirectory = androidComponents.sdkComponents.ndkDirectory
        ndkVersion = libs.versions.ndk
    }
}
val ghosttyKotlin = registerZigBuild("ghosttyKotlin", "kotlin", "ghostty/kotlin")
val ghosttyHostJni = registerZigBuild("ghosttyHostJni", "host", "ghostty/host")

androidComponents {
    onVariants { variant ->
        variant.sources.jniLibs?.addGeneratedSourceDirectory(ghosttyJni, BuildGhosttyNative::outputDir)
        variant.sources.kotlin?.addGeneratedSourceDirectory(ghosttyKotlin, BuildGhosttyNative::outputDir)
    }
}

/** Puts the host JNI library on the unit tests' library path. */
class HostLibraryPath(@get:InputDirectory @get:PathSensitive(PathSensitivity.NONE) val dir: Provider<Directory>) :
    CommandLineArgumentProvider {
    override fun asArguments() = listOf("-Djava.library.path=${dir.get().asFile.absolutePath}")
}

tasks.withType<Test>().configureEach {
    jvmArgumentProviders += HostLibraryPath(ghosttyHostJni.flatMap { it.outputDir })
}

dependencies {
    api(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.coroutines.android)

    api(platform(libs.compose.bom))
    api(libs.compose.ui)
    implementation(libs.compose.foundation)
    implementation(libs.compose.material3)

    testImplementation(libs.kotlin.test.junit)
    testImplementation(libs.kotlinx.coroutines.test)
}
