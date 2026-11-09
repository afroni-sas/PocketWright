plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

// Les architectures embarquées : arm64-v8a (les téléphones) et x86_64 (l'émulateur). Node.js pèse ~85 Mo par
// architecture : -Ppocketwright.abis=arm64-v8a pour un APK de téléphone seulement.
val abis = providers.gradleProperty("pocketwright.abis").getOrElse("arm64-v8a,x86_64").split(",").map { it.trim() }

// Node.js pour Android et playwright-core : téléchargés et préparés avant la compilation.
val prepareEngine = tasks.register<Exec>("prepareEngine") {
    description = "Prépare le moteur Playwright embarqué (nodejs-mobile + playwright-core)"
    workingDir = rootDir
    commandLine("bash", "scripts/prepare-engine.sh")
    inputs.files(
        rootProject.file("scripts/prepare-engine.sh"),
        rootProject.file("engine/package.json"),
        rootProject.file("engine/package-lock.json"),
        rootProject.file("engine/main.js"),
    )
    outputs.dir(layout.buildDirectory.dir("engine-assets"))
    outputs.dir(rootProject.layout.projectDirectory.dir("third_party/nodejs-mobile"))
}
tasks.named("preBuild") { dependsOn(prepareEngine) }

// Une copie à part (autre identifiant, autre port) pour comparer des moteurs sans toucher à l'app installée.
val probe = providers.gradleProperty("pocketwright.probe").isPresent

android {
    namespace = "com.afroni.pocketwright"
    compileSdk = 37
    ndkVersion = "30.0.16248370"
    defaultConfig {
        applicationId = "com.afroni.pocketwright" + if (probe) ".probe" else ""
        buildConfigField("int", "API_PORT", if (probe) "8766" else "8765")
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        versionName = "0.1.0"
        ndk { abiFilters += abis }
        externalNativeBuild {
            cmake { arguments += listOf("-DANDROID_STL=c++_shared") }
        }
    }
    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "4.1.2"
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildTypes {
        release {
            // Sans ça, Compose reste interprété : sur un petit processeur, l'interface saccade.
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            // La clé de développement : l'APK s'installe par-dessus l'existant sans perdre onglets ni moteur.
            signingConfig = signingConfigs.getByName("debug")
            isDebuggable = probe
        }
    }
    packaging {
        // Compressé dans l'APK (libnode.so passe de ~85 à ~30 Mo) ; Android l'extrait à l'installation.
        jniLibs { useLegacyPackaging = true }
        resources {
            excludes += listOf(
                "META-INF/INDEX.LIST",
                "META-INF/DEPENDENCIES",
                "META-INF/versions/9/previous-compilation-data.bin",
                "DebugProbesKt.bin",
                "kotlin-tooling-metadata.json",
            )
        }
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
}

// Le projet Node du moteur part dans les ressources de l'APK (préparé par prepareEngine, avant preBuild).
androidComponents {
    onVariants { variant ->
        variant.sources.assets?.addStaticSourceDirectory("build/engine-assets")
    }
}

dependencies {
    implementation(project(":webviewup"))
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.webkit)
    implementation(libs.androidx.profileinstaller)
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.foundation)
    implementation(libs.compose.material3)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.ktor.server.core)
    implementation(libs.ktor.server.cio)
    implementation(libs.ktor.server.websockets)
    testImplementation(libs.junit)
}
