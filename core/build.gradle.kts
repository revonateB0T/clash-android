import groovy.json.JsonSlurper

plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
	alias(libs.plugins.rust.android)
}

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
    return File(manifestFile.parentFile, "maven/rustls/rustls-platform-verifier/0.1.1/rustls-platform-verifier-0.1.1.aar")
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
    module  = "../uniffi"  // Directory containing Cargo.toml
	libname = "clash_android_ffi"

    extraCargoBuildArguments = arrayListOf("-p", "clash-android-ffi").apply {
        // Enable jemallocator feature on Linux
        if (System.getProperty("os.name").lowercase().contains("linux")) {
            add("--features")
            add("jemallocator")
        }
    }
	targets = listOf("arm64", "arm", "x86", "x86_64")
	profile = "release"
}

android {
    buildToolsVersion = rootProject.extra["buildToolsVersion"] as String
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }
    libraryVariants.all {
        val variant = this
        val variantName = variant.name.replaceFirstChar(Char::titlecase)
        val bDir = layout.projectDirectory.dir("src/main/java")
        val generateBindings = tasks.register("generate${variantName}UniFFIBindings", Exec::class) {
            workingDir = file("../uniffi")
            commandLine(
                "cargo", "run", "-p", "uniffi-bindgen", "generate",
                "--library", "../core/build/rustJniLibs/android/arm64-v8a/libclash_android_ffi.so",
                "--language", "kotlin",
                "--out-dir", bDir.asFile.absolutePath
            )
			dependsOn("cargoBuild")
        }

        // Make Java compilation depend on generating UniFFI bindings
        variant.javaCompileProvider.get().dependsOn(generateBindings)

        // Also hook into Kotlin compilation
        tasks.named("compile${variantName}Kotlin").configure {
            dependsOn(generateBindings)
        }
    }
}
