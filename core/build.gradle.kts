import groovy.json.JsonSlurper

plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.rust.android)
}

val defaultRustTargets = listOf("arm64", "arm", "x86", "x86_64")
val rustTargets =
    providers.gradleProperty("rust-target").orNull
        ?.split(",")
        ?.map(String::trim)
        ?.filter(String::isNotEmpty)
        ?.takeIf { it.isNotEmpty() }
        ?: defaultRustTargets
val rustTargetToAbi =
    mapOf(
        "arm" to "armeabi-v7a",
        "arm64" to "arm64-v8a",
        "x86" to "x86",
        "x86_64" to "x86_64",
    )
val bindingRustTarget = rustTargets.find { it == "arm64" } ?: rustTargets.first()
val bindingAbi =
    requireNotNull(rustTargetToAbi[bindingRustTarget]) {
        "Unsupported rust target for UniFFI binding generation: $bindingRustTarget"
    }
val rustProjectDir = rootProject.layout.projectDirectory.dir("uniffi")

fun String.capitalizeFirst() = replaceFirstChar(Char::uppercaseChar)

fun findRustlsPlatformVerifierClasses(): File {
    val dependencyJson = providers.exec {
        workingDir = File(project.rootDir, "uniffi")
        commandLine("cargo", "metadata", "--format-version", "1")
    }.standardOutput.asText

    val jsonSlurper = JsonSlurper()
    val jsonData = jsonSlurper.parseText(dependencyJson.get()) as Map<*, *>
    val packages = jsonData["packages"] as List<*>
    val path = packages
        .first { element ->
            val pkg = element as Map<*, *>
            pkg["name"] == "rustls-platform-verifier-android"
        }.let { it as Map<*, *> }["manifest_path"] as String

    val manifestFile = File(path)
    return File(manifestFile.parentFile, "classes.jar")
}

android {
    namespace = "rs.clash.android.ffi"
    compileSdk = 36

    ndkVersion = rootProject.extra["ndkVersion"] as String
    defaultConfig {
        minSdk = 23

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_25
        targetCompatibility = JavaVersion.VERSION_25
    }

    buildFeatures {
        compose = true
    }
}

kotlin {
    jvmToolchain(25)
}

dependencies {
	implementation(files(findRustlsPlatformVerifierClasses()))
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.runtime)
    //noinspection Aligned16KB,UseTomlInstead
    implementation("net.java.dev.jna:jna:5.18.1@aar")


    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
}

cargo {
    module = rustProjectDir.asFile.absolutePath
    targetDirectory = rustProjectDir.dir("target").asFile.absolutePath
    libname = "clash_android_ffi"
    targets = rustTargets

    extraCargoBuildArguments = arrayListOf("-p", "clash-android-ffi").apply {
        // Enable jemallocator feature on Linux
        if (System.getProperty("os.name").lowercase().contains("linux")) {
            add("--features")
            add("jemallocator")
        }
    }
    profile = "release"
}

val cargoBuildTask = tasks.named("cargoBuild")
val cargoBuildBindingTargetTask =
    tasks.named("cargoBuild${bindingRustTarget.capitalizeFirst()}")

android {
    buildToolsVersion = rootProject.extra["buildToolsVersion"] as String
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_25
        targetCompatibility = JavaVersion.VERSION_25
    }
    libraryVariants.all {
        val variant = this
        val variantName = variant.name.capitalizeFirst()
        val bDir = layout.projectDirectory.dir("src/main/java")
        val rustJniLibsDir = layout.buildDirectory.dir("rustJniLibs/android").get()
        val bindingLibrary =
            layout.buildDirectory.file("rustJniLibs/android/$bindingAbi/libclash_android_ffi.so")
        tasks.named("merge${variantName}JniLibFolders").configure {
            inputs.dir(rustJniLibsDir)
            dependsOn(cargoBuildTask)
        }
        val generateBindings = tasks.register("generate${variantName}UniFFIBindings", Exec::class) {
            workingDir = rustProjectDir.asFile
            commandLine(
                "cargo", "run", "-p", "uniffi-bindgen", "generate",
                "--library", bindingLibrary.get().asFile.absolutePath,
                "--language", "kotlin",
                "--out-dir", bDir.asFile.absolutePath
            )
            dependsOn(cargoBuildBindingTargetTask)
        }

        // Make Java compilation depend on generating UniFFI bindings
        variant.javaCompileProvider.get().dependsOn(generateBindings)

        // Also hook into Kotlin compilation
        tasks.named("compile${variantName}Kotlin").configure {
            dependsOn(generateBindings)
        }

        // And connectedDebugAndroidTest
//        tasks.named("connected${variantName}AndroidTest").configure {
//            dependsOn(generateBindings)
//        }
    }
}
