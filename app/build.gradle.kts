import java.net.URI
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.Properties
import org.gradle.api.DefaultTask
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.TaskAction
import org.gradle.work.DisableCachingByDefault

plugins {
    id("com.android.application")
}

@DisableCachingByDefault(
    because = "The task keeps hash-verified model files in the local build directory.",
)
abstract class PreparePpOcrv6AssetsTask : DefaultTask() {
    @get:OutputDirectory
    abstract val outputDirectory: DirectoryProperty

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().buffered().use { input ->
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
        }
        return digest.digest().joinToString("") { byte -> "%02X".format(byte) }
    }

    private fun downloadVerified(
        url: String,
        expectedSha256: String,
        target: File,
    ) {
        if (target.isFile && sha256(target) == expectedSha256) return
        target.parentFile.mkdirs()
        val partial = target.resolveSibling("${target.name}.part")
        val connection = URI(url).toURL().openConnection().apply {
            connectTimeout = 30_000
            readTimeout = 180_000
        }
        connection.getInputStream().buffered().use { input ->
            partial.outputStream().buffered().use { output -> input.copyTo(output) }
        }
        check(sha256(partial) == expectedSha256) {
            "SHA-256 mismatch for $url"
        }
        Files.move(
            partial.toPath(),
            target.toPath(),
            StandardCopyOption.REPLACE_EXISTING,
        )
    }

    @TaskAction
    fun prepare() {
        val assetDirectory = outputDirectory.get().dir("ppocrv6_small")
        val detectionAsset = assetDirectory.file("det.onnx").asFile
        val recognitionAsset = assetDirectory.file("rec.onnx").asFile
        val charactersAsset = assetDirectory.file("characters.txt").asFile
        downloadVerified(
            url = "https://huggingface.co/PaddlePaddle/" +
                "PP-OCRv6_small_det_onnx/resolve/" +
                "28fe5895c24fd108c19eb3e8479f4ab385fbfc62/" +
                "inference.onnx?download=true",
            expectedSha256 =
                "D73E0058B7A8086BBD57F3D10B8BCD4FF95363F67E06E2762B5E814FE9C9410E",
            target = detectionAsset,
        )
        downloadVerified(
            url = "https://huggingface.co/PaddlePaddle/" +
                "PP-OCRv6_small_rec_onnx/resolve/" +
                "b8f84f0b80c529de40b4fbb3544b84fa7233a513/" +
                "inference.onnx?download=true",
            expectedSha256 =
                "5435FD747C9E0EFE15A96D0B378D5BD157E9492ED8FD80EDF08F30D02FA24634",
            target = recognitionAsset,
        )

        val recognitionYaml = temporaryDir.resolve("rec-inference.yml")
        downloadVerified(
            url = "https://huggingface.co/PaddlePaddle/" +
                "PP-OCRv6_small_rec_onnx/resolve/" +
                "b8f84f0b80c529de40b4fbb3544b84fa7233a513/" +
                "inference.yml?download=true",
            expectedSha256 =
                "AB078671BB49F06228EADCCD34F1BB501E157F7A047095FFB943BA81512C77D1",
            target = recognitionYaml,
        )
        val yamlLines = recognitionYaml.readLines(Charsets.UTF_8)
        val dictionaryStart = yamlLines.indexOf("  character_dict:")
        check(dictionaryStart >= 0)
        val characters = yamlLines
            .drop(dictionaryStart + 1)
            .takeWhile { line -> line.startsWith("  - ") }
            .map { line ->
                val scalar = line.removePrefix("  - ")
                if (scalar.length >= 2 && scalar.first() == '\'' && scalar.last() == '\'') {
                    scalar.substring(1, scalar.lastIndex).replace("''", "'")
                } else {
                    scalar
                }
            }
        check(characters.size == 18_708)
        check(characters.all { character ->
            character.codePointCount(0, character.length) == 1
        })
        charactersAsset.parentFile.mkdirs()
        charactersAsset.writeText(
            characters.joinToString(separator = "\n", postfix = "\n"),
            Charsets.UTF_8,
        )
        check(
            sha256(charactersAsset) ==
                "B5F2BFE2BDD9448429E3E82B51C789775D9B42F2403D082B00662EB77E401C5D",
        )
    }
}

val preparePpOcrv6Assets =
    tasks.register<PreparePpOcrv6AssetsTask>(
        "preparePpOcrv6Assets",
    ) {
        group = "build setup"
        description =
            "Downloads pinned and hash-verified PP-OCRv6 small ONNX assets."
        outputDirectory.set(
            layout.buildDirectory.dir("generated/ppocrv6Assets"),
        )
    }

val bergamotRunnerFile =
    layout.projectDirectory.file(
        "src/lite/jniLibs/arm64-v8a/libbergamot_runner.so",
    )
val verifyBergamotRunner = tasks.register("verifyBergamotRunner") {
    group = "verification"
    description = "Verifies the pinned Bergamot Lite ARM64 runner."
    inputs.file(bergamotRunnerFile)
    doLast {
        val runner = bergamotRunnerFile.asFile
        check(runner.isFile && runner.length() == 8_416_304L) {
            "Missing or invalid Bergamot Lite runner: $runner"
        }
        val digest = MessageDigest.getInstance("SHA-256")
        runner.inputStream().buffered().use { input ->
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
        }
        val hash = digest.digest().joinToString("") { byte -> "%02X".format(byte) }
        check(
            hash ==
                "40A764D5FBCD8B18C6C0BEF6BCC6EF38F25BEB6F2E6DEFCFA5C00D7E54407F75",
        ) {
            "Bergamot Lite runner SHA-256 mismatch: $hash"
        }
    }
}

val releaseSigningPropertiesFile = rootProject.file("keystore.properties")
val releaseSigningKeys = listOf("storeFile", "storePassword", "keyAlias", "keyPassword")
val releaseSigningEnvironment = mapOf(
    "storeFile" to "ANDROID_RELEASE_STORE_FILE",
    "storePassword" to "ANDROID_RELEASE_STORE_PASSWORD",
    "keyAlias" to "ANDROID_RELEASE_KEY_ALIAS",
    "keyPassword" to "ANDROID_RELEASE_KEY_PASSWORD",
)
val releaseSigningProperties = Properties().apply {
    if (releaseSigningPropertiesFile.isFile) {
        releaseSigningPropertiesFile.inputStream().use { load(it) }
    }
}
val releaseSigningValues = releaseSigningKeys.associateWith { key ->
    providers.environmentVariable(releaseSigningEnvironment.getValue(key)).orNull
        ?: releaseSigningProperties.getProperty(key)
}
val releaseSigningRequested = releaseSigningPropertiesFile.isFile ||
    releaseSigningValues.values.any { !it.isNullOrBlank() }
val missingReleaseSigningKeys = releaseSigningKeys.filter {
    releaseSigningValues.getValue(it).isNullOrBlank()
}
require(!releaseSigningRequested || missingReleaseSigningKeys.isEmpty()) {
    "Release signing is partially configured; missing: ${missingReleaseSigningKeys.joinToString()}"
}
val releaseSigningConfigured = releaseSigningRequested && missingReleaseSigningKeys.isEmpty()
android {
    namespace = "com.screentranslation.app"
    // androidx.core 1.19.0+ requires compiling against API 37 or newer.
    // targetSdk stays at 36: this changes what APIs are compilable, not runtime behaviour.
    compileSdk = 37
    buildToolsVersion = "37.0.0"

    defaultConfig {
        applicationId = "com.screentranslation.app"
        minSdk = 36
        targetSdk = 36
        versionCode = 5
        versionName = "0.3.1"
        buildConfigField("boolean", "BERGAMOT_LITE", "false")
        buildConfigField("boolean", "HYMT2_Q4_EXPERIMENTAL", "false")
        buildConfigField("boolean", "ONLINE_LLM", "false")

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        // Package only the Xiaomi 15 Pro target ABI.
        ndk {
            abiFilters += "arm64-v8a"
        }
    }

    signingConfigs {
        if (releaseSigningConfigured) {
            create("release") {
                storeFile = rootProject.file(
                    checkNotNull(releaseSigningValues.getValue("storeFile")),
                )
                storePassword = releaseSigningValues.getValue("storePassword")
                keyAlias = releaseSigningValues.getValue("keyAlias")
                keyPassword = releaseSigningValues.getValue("keyPassword")
            }
        }
    }

    flavorDimensions += "edition"
    productFlavors {
        create("lite") {
            dimension = "edition"
            versionNameSuffix = "-lite"
            buildConfigField("boolean", "BERGAMOT_LITE", "true")
        }
        create("full") {
            dimension = "edition"
            applicationIdSuffix = ".full"
            versionNameSuffix = "-full"
            buildConfigField("boolean", "HYMT2_Q4_EXPERIMENTAL", "true")
        }
        create("online") {
            dimension = "edition"
            applicationIdSuffix = ".online"
            versionNameSuffix = "-online"
            buildConfigField("boolean", "ONLINE_LLM", "true")
        }
    }

    sourceSets {
        getByName("main").assets.srcDir(
            rootProject.file("third_party/licenses/common"),
        )
        getByName("lite").assets.srcDir(
            rootProject.file("third_party/licenses/lite"),
        )
        getByName("full").assets.srcDir(
            rootProject.file("third_party/licenses/full"),
        )
        getByName("online").assets.srcDir(
            rootProject.file("third_party/licenses/online"),
        )
    }

    buildTypes {
        debug {
            versionNameSuffix = "-debug"
        }
        create("benchmark") {
            initWith(getByName("debug"))
            applicationIdSuffix = ".benchmark"
            versionNameSuffix = "-benchmark"
            matchingFallbacks += listOf("debug")
        }
        release {
            if (releaseSigningConfigured) {
                signingConfig = signingConfigs.getByName("release")
            }
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        viewBinding = true
        buildConfig = true
    }

    androidResources {
        noCompress += "onnx"
    }

    packaging {
        jniLibs {
            // The Bergamot runner is an executable PIE stored in nativeLibraryDir.
            // Legacy packaging makes PackageManager extract it with execute bits.
            useLegacyPackaging = true
            keepDebugSymbols += "**/libbergamot_runner.so"
        }
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }

    lint {
        abortOnError = true
        checkReleaseBuilds = true
    }
}

androidComponents {
    beforeVariants(selector().withBuildType("benchmark")) { variant ->
        val applicationVariant =
            variant as com.android.build.api.variant.ApplicationVariantBuilder
        applicationVariant.hostTests[
            com.android.build.api.variant.HostTestBuilder.UNIT_TEST_TYPE
        ]?.enable = true
    }
    onVariants(selector().all()) { variant ->
        variant.sources.assets?.addGeneratedSourceDirectory(
            preparePpOcrv6Assets,
            PreparePpOcrv6AssetsTask::outputDirectory,
        )
    }
}

tasks.configureEach {
    if (name.startsWith("mergeLite") && name.endsWith("NativeLibs")) {
        dependsOn(verifyBergamotRunner)
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.19.0")
    implementation("androidx.activity:activity-ktx:1.13.0")
    implementation("androidx.appcompat:appcompat:1.7.1")
    implementation("com.google.android.material:material:1.14.0")

    // Production OCR: pinned PP-OCRv6 ONNX assets with an on-device ARM64 runtime.
    implementation("com.microsoft.onnxruntime:onnxruntime-android:1.28.0")

    // ML Kit Translate remains isolated to the benchmark build type.
    add("benchmarkImplementation", "com.google.mlkit:translate:17.0.3")

    // The Full edition contains the Hy-MT2 Q4 llama.cpp runtime.
    add("fullImplementation", project(":llama-android"))

    // The Online edition sends OCR text only to the API configured by the user.
    add("onlineImplementation", "com.squareup.okhttp3:okhttp:5.4.0")

    // ML Kit OCR remains benchmark-only as the v0.1.0 comparison baseline.
    add("benchmarkImplementation", "com.google.mlkit:text-recognition:16.0.1")
    add("benchmarkImplementation", "com.google.mlkit:text-recognition-chinese:16.0.1")
    add("benchmarkImplementation", "com.google.mlkit:text-recognition-japanese:16.0.1")
    add("benchmarkImplementation", "com.google.mlkit:text-recognition-korean:16.0.1")

    testImplementation("junit:junit:4.13.2")
    add("testOnlineImplementation", "org.json:json:20260719")
}

tasks.register("printVersionInfo") {
    group = "help"
    description = "Prints machine-readable Android version values for release automation."
    doLast {
        println("versionName=${android.defaultConfig.versionName}")
        println("versionCode=${android.defaultConfig.versionCode}")
    }
}
