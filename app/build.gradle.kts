import java.net.URI
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.Properties
import groovy.json.JsonOutput
import org.gradle.api.artifacts.component.ModuleComponentIdentifier
import org.gradle.api.DefaultTask
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import org.gradle.api.tasks.testing.Test
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
        versionCode = 10
        versionName = "2.4.0"
        buildConfigField("boolean", "BERGAMOT_LITE", "false")
        buildConfigField("boolean", "HYMT2_Q4_EXPERIMENTAL", "false")
        buildConfigField("boolean", "ONLINE_LLM", "false")
        buildConfigField("String", "EDITION_ID", "\"unknown\"")
        buildConfigField("String", "OCR_BACKEND_ID", "\"ppocrv6-small-onnx\"")
        buildConfigField("String", "TRANSLATION_BACKEND_ID", "\"unknown\"")

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
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
            buildConfigField("String", "EDITION_ID", "\"lite\"")
            buildConfigField("String", "TRANSLATION_BACKEND_ID", "\"bergamot-lite\"")
        }
        create("full") {
            dimension = "edition"
            applicationIdSuffix = ".full"
            versionNameSuffix = "-full"
            buildConfigField("boolean", "HYMT2_Q4_EXPERIMENTAL", "true")
            buildConfigField("String", "EDITION_ID", "\"full\"")
            buildConfigField("String", "TRANSLATION_BACKEND_ID", "\"hymt2-q4\"")
        }
        create("online") {
            dimension = "edition"
            applicationIdSuffix = ".online"
            versionNameSuffix = "-online"
            buildConfigField("boolean", "ONLINE_LLM", "true")
            buildConfigField("String", "EDITION_ID", "\"online\"")
            buildConfigField("String", "TRANSLATION_BACKEND_ID", "\"online-byok\"")
        }
    }

    sourceSets {
        getByName("main").assets.srcDir(
            rootProject.file("third_party/licenses/common"),
        )
        getByName("testOnline").resources.srcDir(
            rootProject.file("tools/model-benchmark/fixtures"),
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
            // Product/debug installs still target the Xiaomi 15 Pro ABI.
            ndk {
                abiFilters += "arm64-v8a"
            }
        }
        create("benchmark") {
            initWith(getByName("debug"))
            applicationIdSuffix = ".benchmark"
            versionNameSuffix = "-benchmark"
            matchingFallbacks += listOf("debug")
        }
        create("instrumentation") {
            initWith(getByName("debug"))
            applicationIdSuffix = ".instrumentation"
            versionNameSuffix = "-instrumentation"
            matchingFallbacks += listOf("debug")
            // CI runs only Online instrumentation on an Android 16 x86_64 emulator.
            ndk {
                abiFilters.clear()
                abiFilters += "x86_64"
            }
        }
        create("contributor") {
            initWith(getByName("debug"))
            applicationIdSuffix = ".contributor"
            versionNameSuffix = "-contributor"
            matchingFallbacks += listOf("debug")
            // The contributor/emulator path is intentionally Online-only: it
            // exercises PP-OCRv6 through ONNX Runtime x86_64 without allowing
            // x86_64 libraries to enter any Release edition.
            ndk {
                abiFilters.clear()
                abiFilters += "x86_64"
            }
        }
        release {
            ndk {
                abiFilters += "arm64-v8a"
            }
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
    beforeVariants(selector().withBuildType("instrumentation")) { variant ->
        val edition = variant.productFlavors
            .firstOrNull { (dimension, _) -> dimension == "edition" }
            ?.second
        variant.enable = edition == "online"
        if (variant.enable) {
            val applicationVariant =
                variant as com.android.build.api.variant.ApplicationVariantBuilder
            applicationVariant.deviceTests[
                com.android.build.api.variant.DeviceTestBuilder.ANDROID_TEST_TYPE
            ]?.enable = true
        }
    }
    beforeVariants(selector().withBuildType("benchmark")) { variant ->
        val applicationVariant =
            variant as com.android.build.api.variant.ApplicationVariantBuilder
        applicationVariant.hostTests[
            com.android.build.api.variant.HostTestBuilder.UNIT_TEST_TYPE
        ]?.enable = true
    }
    beforeVariants(selector().withBuildType("contributor")) { variant ->
        val edition = variant.productFlavors
            .firstOrNull { (dimension, _) -> dimension == "edition" }
            ?.second
        variant.enable = edition == "online"
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
    implementation("androidx.work:work-runtime-ktx:2.11.2")

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
    testImplementation("org.json:json:20260719")
    add("testOnlineImplementation", "com.squareup.okhttp3:mockwebserver3:5.4.0")
    add("testOnlineImplementation", "com.squareup.okhttp3:okhttp-tls:5.4.0")

    androidTestImplementation("androidx.test:core-ktx:1.7.0")
    androidTestImplementation("androidx.test:runner:1.7.0")
    androidTestImplementation("androidx.test:rules:1.7.0")
    androidTestImplementation("androidx.test.ext:junit:1.3.0")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.7.0")
}

tasks.register("printVersionInfo") {
    group = "help"
    description = "Prints machine-readable Android version values for release automation."
    doLast {
        println("versionName=${android.defaultConfig.versionName}")
        println("versionCode=${android.defaultConfig.versionCode}")
    }
}

fun sbomComponent(
    type: String,
    name: String,
    version: String,
    purl: String,
    licenseId: String,
    sha256: String? = null,
): Map<String, Any> = linkedMapOf<String, Any>(
    "type" to type,
    "name" to name,
    "version" to version,
    "bom-ref" to purl,
    "purl" to purl,
    "licenses" to if (licenseId == "NOASSERTION") {
        listOf(mapOf("expression" to "NOASSERTION"))
    } else {
        listOf(mapOf("license" to mapOf("id" to licenseId)))
    },
).apply {
    if (sha256 != null) {
        this["hashes"] = listOf(mapOf("alg" to "SHA-256", "content" to sha256.uppercase()))
    }
}

fun resolvedDependencyLicense(group: String): String = when {
    group.startsWith("androidx.") -> "Apache-2.0"
    group.startsWith("com.google.android.material") -> "Apache-2.0"
    group.startsWith("com.google.errorprone") -> "Apache-2.0"
    group.startsWith("com.google.guava") -> "Apache-2.0"
    group.startsWith("com.microsoft.onnxruntime") -> "MIT"
    group.startsWith("com.squareup.okhttp3") -> "Apache-2.0"
    group.startsWith("com.squareup.okio") -> "Apache-2.0"
    group.startsWith("org.jetbrains") -> "Apache-2.0"
    group.startsWith("org.jspecify") -> "Apache-2.0"
    else -> error("Add an explicit SBOM license mapping for Gradle group: $group")
}

val commonSbomComponents = listOf(
    sbomComponent(
        type = "machine-learning-model",
        name = "PP-OCRv6-small detection",
        version = "28fe5895c24fd108c19eb3e8479f4ab385fbfc62",
        purl = "pkg:huggingface/PaddlePaddle/PP-OCRv6_small_det_onnx@28fe5895c24fd108c19eb3e8479f4ab385fbfc62",
        licenseId = "Apache-2.0",
        sha256 = "D73E0058B7A8086BBD57F3D10B8BCD4FF95363F67E06E2762B5E814FE9C9410E",
    ),
    sbomComponent(
        type = "machine-learning-model",
        name = "PP-OCRv6-small recognition",
        version = "b8f84f0b80c529de40b4fbb3544b84fa7233a513",
        purl = "pkg:huggingface/PaddlePaddle/PP-OCRv6_small_rec_onnx@b8f84f0b80c529de40b4fbb3544b84fa7233a513",
        licenseId = "Apache-2.0",
        sha256 = "5435FD747C9E0EFE15A96D0B378D5BD157E9492ED8FD80EDF08F30D02FA24634",
    ),
)

val editionSbomComponents = mapOf(
    "lite" to listOf(
        sbomComponent(
            type = "library",
            name = "Bergamot Translator Android runner",
            version = "9271618ebbdc5d21ac4dc4df9e72beb7ce644774",
            purl = "pkg:github/browsermt/bergamot-translator@9271618ebbdc5d21ac4dc4df9e72beb7ce644774",
            licenseId = "MPL-2.0",
            sha256 = "40A764D5FBCD8B18C6C0BEF6BCC6EF38F25BEB6F2E6DEFCFA5C00D7E54407F75",
        ),
        sbomComponent(
            type = "machine-learning-model",
            name = "Firefox Translations en-zh and ja-en routes",
            version = "e7957fc407441a5e3e35bbcbf9d60d9b35764618",
            purl = "pkg:github/mozilla/firefox-translations-models@e7957fc407441a5e3e35bbcbf9d60d9b35764618",
            licenseId = "MPL-2.0",
        ),
    ),
    "full" to listOf(
        sbomComponent(
            type = "library",
            name = "llama.cpp Android runtime",
            version = "caa596ab3f0f8768ee326d6e3d5d39782194676c",
            purl = "pkg:github/ggml-org/llama.cpp@caa596ab3f0f8768ee326d6e3d5d39782194676c",
            licenseId = "MIT",
        ),
        sbomComponent(
            type = "machine-learning-model",
            name = "HY-MT2 1.8B Q4_K_M",
            version = "1cd5208700acedef4ef93019b6cfc148b8522d45",
            purl = "pkg:huggingface/tencent/Hy-MT2-1.8B-GGUF@1cd5208700acedef4ef93019b6cfc148b8522d45",
            licenseId = "Apache-2.0",
            sha256 = "DC5F44FCF1FA496EE7AD725982C0C8C553A4DE00259B53AF84C4B89FB0C06699",
        ),
    ),
    "online" to listOf(
        sbomComponent(
            type = "machine-learning-model",
            name = "User-configured OpenAI-compatible model",
            version = "runtime-selected",
            purl = "pkg:generic/screentranslation/online-model@runtime-selected",
            licenseId = "NOASSERTION",
        ),
    ),
)

fun registerEditionSbom(edition: String) = tasks.register("generate${edition.replaceFirstChar(Char::uppercase)}ReleaseSbom") {
    group = "reporting"
    description = "Generates a CycloneDX 1.5 SBOM for the $edition Release edition."
    val output = layout.buildDirectory.file(
        "reports/sbom/ScreenTranslation-${android.defaultConfig.versionName}-$edition.cdx.json",
    )
    outputs.file(output)
    doLast {
        val configurationName = "${edition}ReleaseRuntimeClasspath"
        val gradleComponents = configurations.getByName(configurationName)
            .incoming.resolutionResult.allComponents
            .mapNotNull { component ->
                val id = component.id as? ModuleComponentIdentifier ?: return@mapNotNull null
                val purl = "pkg:maven/${id.group}/${id.module}@${id.version}"
                linkedMapOf<String, Any>(
                    "type" to "library",
                    "group" to id.group,
                    "name" to id.module,
                    "version" to id.version,
                    "bom-ref" to purl,
                    "purl" to purl,
                    "licenses" to listOf(
                        mapOf(
                            "license" to mapOf(
                                "id" to resolvedDependencyLicense(id.group),
                            ),
                        ),
                    ),
                )
            }
            .distinctBy { component -> component.getValue("bom-ref") }
            .sortedBy { component -> component.getValue("bom-ref").toString() }
        val applicationIdSuffix = if (edition == "lite") "" else ".$edition"
        val applicationPurl =
            "pkg:apk/com.screentranslation.app$applicationIdSuffix" +
                "@${android.defaultConfig.versionName}?edition=$edition"
        val components = (gradleComponents + commonSbomComponents + editionSbomComponents.getValue(edition))
            .distinctBy { component -> component.getValue("bom-ref") }
            .sortedBy { component -> component.getValue("bom-ref").toString() }
        val bom = linkedMapOf<String, Any>(
            "bomFormat" to "CycloneDX",
            "specVersion" to "1.5",
            "version" to 1,
            "metadata" to mapOf(
                "component" to mapOf(
                    "type" to "application",
                    "name" to "ScreenTranslation-$edition",
                    "version" to android.defaultConfig.versionName.toString(),
                    "bom-ref" to applicationPurl,
                    "purl" to applicationPurl,
                    "licenses" to listOf(mapOf("license" to mapOf("id" to "Apache-2.0"))),
                ),
                "properties" to listOf(
                    mapOf("name" to "screentranslation:edition", "value" to edition),
                    mapOf("name" to "screentranslation:targetSdk", "value" to "36"),
                    mapOf("name" to "screentranslation:releaseAbi", "value" to "arm64-v8a"),
                ),
            ),
            "components" to components,
            "dependencies" to listOf(
                mapOf(
                    "ref" to applicationPurl,
                    "dependsOn" to components.map { component ->
                        component.getValue("bom-ref")
                    },
                ),
            ),
        )
        val destination = output.get().asFile
        destination.parentFile.mkdirs()
        val rendered = JsonOutput.prettyPrint(JsonOutput.toJson(bom)) + "\n"
        check(!Regex("(?i)([a-z]:\\\\|/home/|/users/|\\\\\\\\)").containsMatchIn(rendered)) {
            "SBOM contains a local filesystem path"
        }
        destination.writeText(rendered, Charsets.UTF_8)
    }
}

val generateLiteReleaseSbom = registerEditionSbom("lite")
val generateFullReleaseSbom = registerEditionSbom("full")
val generateOnlineReleaseSbom = registerEditionSbom("online")

tasks.register("generateReleaseSboms") {
    group = "reporting"
    description = "Generates CycloneDX SBOMs for Lite, Full, and Online Release editions."
    dependsOn(generateLiteReleaseSbom, generateFullReleaseSbom, generateOnlineReleaseSbom)
}

// Formal Online failure evidence is challenge-driven. A random challenge makes
// the unit-test task out-of-date, and the output path is owned by the invoking
// gate. Ordinary testOnlineDebugUnitTest runs receive neither property and do
// not leave a reusable formal-evidence JSON file behind.
val onlineEvidenceChallengeFile =
    providers.gradleProperty("onlineFailureEvidenceChallengeFile")
val onlineEvidenceOutputFile =
    providers.gradleProperty("onlineFailureEvidenceOutputFile")

tasks.withType<Test>().configureEach {
    if (name == "testOnlineDebugUnitTest" &&
        (onlineEvidenceChallengeFile.isPresent || onlineEvidenceOutputFile.isPresent)
    ) {
        require(onlineEvidenceChallengeFile.isPresent && onlineEvidenceOutputFile.isPresent) {
            "Fresh Online evidence requires both -PonlineFailureEvidenceChallengeFile " +
                "and -PonlineFailureEvidenceOutputFile"
        }
        val challenge = file(onlineEvidenceChallengeFile.get())
        val evidence = file(onlineEvidenceOutputFile.get())
        inputs.file(challenge)
            .withPropertyName("onlineFailureEvidenceChallenge")
            .withPathSensitivity(PathSensitivity.NONE)
        outputs.file(evidence)
            .withPropertyName("onlineFailureEvidenceResponse")
        // The fresh nonce already changes the input fingerprint. Disabling the
        // up-to-date shortcut also forces a new current-checkout execution.
        outputs.upToDateWhen { false }
        systemProperty(
            "screenTranslation.onlineEvidence.challengeFile",
            challenge.absolutePath,
        )
        systemProperty(
            "screenTranslation.onlineEvidence.outputFile",
            evidence.absolutePath,
        )
        filter {
            includeTestsMatching(
                "com.screentranslation.app.online.OnlineFailureContractExecutionTest",
            )
        }
        doFirst {
            check(challenge.isFile) {
                "Fresh Online evidence challenge is missing: $challenge"
            }
            check(!evidence.exists()) {
                "Fresh Online evidence output must not already exist: $evidence"
            }
        }
    }
}

tasks.register("generateFreshOnlineFailureEvidence") {
    group = "verification"
    description =
        "Runs the production Online policy/parser against a one-use gate challenge."
    dependsOn("testOnlineDebugUnitTest")
    doLast {
        check(onlineEvidenceChallengeFile.isPresent && onlineEvidenceOutputFile.isPresent) {
            "Pass challenge/output paths with the onlineFailureEvidence Gradle properties"
        }
        val evidence = file(onlineEvidenceOutputFile.get())
        check(evidence.isFile && evidence.length() > 0L) {
            "Fresh Online failure evidence was not produced: $evidence"
        }
    }
}
