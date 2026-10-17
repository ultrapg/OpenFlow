/*
 * Copyright 2026 OpenFlow Contributors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("com.google.devtools.ksp") version "2.1.0-1.0.29"
}

android {
    namespace = "com.voicebubble.openflow"
    compileSdk = 35

    androidResources {
        noCompress += listOf("tflite", "f16", "f32")
    }

    defaultConfig {
        applicationId = "com.voicebubble.openflow"
        minSdk = 26
        targetSdk = 35
        versionCode = 2
        versionName = "2.1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        vectorDrawables {
            useSupportLibrary = true
        }

        ndk {
            val includeX86 = project.findProperty("includeX86")?.toString()?.toBoolean() == true
            if (includeX86) {
                abiFilters += listOf("arm64-v8a", "x86_64")
            } else {
                abiFilters += listOf("arm64-v8a")
            }
        }
    }

    sourceSets {
        getByName("main") {
            val bundledCacheDir = file("/home/marvin/.cache/openflow-bundled-assets")
            val bundleOffline = project.findProperty("bundleOfflineModels")?.toString()?.toBoolean() == true
            if (bundleOffline && bundledCacheDir.exists()) {
                assets.srcDirs("src/main/assets", bundledCacheDir.absolutePath)
            }
        }
    }

    signingConfigs {
        create("release") {
            val keystoreFile = file("../release.jks")
            if (keystoreFile.exists()) {
                storeFile = keystoreFile
                storePassword = "password"
                keyAlias = "key0"
                keyPassword = "password"
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            signingConfig = signingConfigs.getByName("release")
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    buildFeatures {
        compose = true
    }
    packaging {
        jniLibs {
            useLegacyPackaging = true
        }
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2024.12.01")
    implementation(composeBom)
    androidTestImplementation(composeBom)

    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    implementation("androidx.activity:activity-compose:1.10.0")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")

    // Accompanist for permissions
    implementation("com.google.accompanist:accompanist-permissions:0.36.0")

    // Navigation Compose
    implementation("androidx.navigation:navigation-compose:2.8.5")

    // Room
    val roomVersion = "2.6.1"
    implementation("androidx.room:room-runtime:$roomVersion")
    implementation("androidx.room:room-ktx:$roomVersion")
    ksp("androidx.room:room-compiler:$roomVersion")

    // DataStore
    implementation("androidx.datastore:datastore-preferences:1.1.2")

    // Coroutines
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")

    // Lifecycle service
    implementation("androidx.lifecycle:lifecycle-service:2.8.7")

    // Desert Ant Labs Audio AI Ecosystem (LiteRT 2.2.0 CPU + GPU/OpenCL accelerator bundled via ai.desertant:core:4.0.0)
    implementation("ai.desertant:voz:4.0.0")
    implementation("ai.desertant:ear:4.0.0")
    implementation("ai.desertant:clear:4.0.0")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.9.0")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.6.1")
    androidTestImplementation("androidx.compose.ui:ui-test-junit4")
    debugImplementation("androidx.compose.ui:ui-tooling")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
}

