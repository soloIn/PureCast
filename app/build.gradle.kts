import java.io.File
import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    jacoco
}

val localProperties = Properties().apply {
    val localPropsFile = rootProject.file("local.properties")
    if (localPropsFile.exists()) {
        localPropsFile.inputStream().use { load(it) }
    }
}

fun resolveSigningProp(envName: String, propName: String): String? {
    return System.getenv(envName)?.takeIf { it.isNotBlank() }
        ?: localProperties.getProperty(propName)?.takeIf { it.isNotBlank() }
}

val releaseStoreFilePath = resolveSigningProp("RELEASE_STORE_FILE", "release.storeFile")
val releaseStorePassword = resolveSigningProp("RELEASE_STORE_PASSWORD", "release.storePassword")
val releaseKeyAlias = resolveSigningProp("RELEASE_KEY_ALIAS", "release.keyAlias")
val releaseKeyPassword = resolveSigningProp("RELEASE_KEY_PASSWORD", "release.keyPassword")
val releaseStoreFileResolved = releaseStoreFilePath?.let { path ->
    val rawFile = File(path)
    if (rawFile.isAbsolute) rawFile else rootProject.file(path)
}?.takeIf { it.exists() }
val hasReleaseSigning = releaseStoreFileResolved != null &&
    releaseStorePassword != null &&
    releaseKeyAlias != null &&
    releaseKeyPassword != null

base {
    archivesName.set("PureCast")
}

android {
    namespace = "com.solo.purecast"
    compileSdk = 34
    ndkVersion = "26.3.11579264"

    defaultConfig {
        applicationId = "com.solo.purecast"
        minSdk = 30
        targetSdk = 34
        versionCode = 1
        versionName = "1.0.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        ndk {
            abiFilters += listOf("armeabi-v7a", "arm64-v8a")
        }

        externalNativeBuild {
            cmake {
                cppFlags += "-std=c++20"
                arguments += listOf(
                    "-DANDROID_STL=c++_static"
                )
            }
        }
    }

    splits {
        abi {
            isEnable = true
            reset()
            include("armeabi-v7a", "arm64-v8a")
            isUniversalApk = true
        }
    }

    signingConfigs {
        if (hasReleaseSigning) {
            create("release") {
                storeFile = releaseStoreFileResolved
                storePassword = releaseStorePassword
                keyAlias = releaseKeyAlias
                keyPassword = releaseKeyPassword
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            signingConfig = if (hasReleaseSigning) {
                signingConfigs.getByName("release")
            } else {
                signingConfigs.getByName("debug")
            }
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
        debug {
            isDebuggable = true
            isJniDebuggable = true
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }



    buildFeatures {
        compose = true
    }

    externalNativeBuild {
        cmake {
            path = file("CMakeLists.txt")
            version = "3.22.1"
        }
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }

    testOptions {
        unitTests.isReturnDefaultValues = true
        unitTests.isIncludeAndroidResources = true
        unitTests.all {
            it.extensions.configure(JacocoTaskExtension::class.java) {
                isIncludeNoLocationClasses = true
                excludes = listOf("jdk.internal.*")
            }
        }
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

jacoco {
    toolVersion = "0.8.12"
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.service)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.compose.animation)

    // Compose for TV
    implementation(libs.androidx.tv.material)
    implementation(libs.androidx.palette.ktx)
    implementation(libs.coil.compose)

    // Coroutines
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.coroutines.android)

    // UPnP & Network
    implementation(libs.mmupnp)
    implementation(libs.ktor.client.core)
    implementation(libs.ktor.client.cio)
    implementation(libs.jmdns)

    // NLP & String Similarity
    implementation(libs.fuzzywuzzy)

    // Testing
    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.core)
    androidTestImplementation(libs.junit)
    androidTestImplementation(libs.androidx.test.core)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
}

// 💡 焦点与状态转换核心单元覆盖率强校验门禁
val sessionClassesTree = fileTree(layout.buildDirectory.dir("tmp/kotlin-classes/debug")) {
    include(
        "com/solo/purecast/core/session/PlaybackSessionCoordinator*.class",
        "com/solo/purecast/core/session/TrackTransactionManager*.class",
        "com/solo/purecast/core/session/TriStateTransitionMatrix*.class"
    )
}

val jacocoTestReport by tasks.registering(JacocoReport::class) {
    dependsOn("testDebugUnitTest")
    reports {
        xml.required.set(true)
        html.required.set(true)
    }
    sourceDirectories.setFrom(files("${project.projectDir}/src/main/java"))
    classDirectories.setFrom(files(sessionClassesTree))
    executionData.setFrom(files(layout.buildDirectory.file("jacoco/testDebugUnitTest.exec")))
}

val jacocoTestCoverageVerification by tasks.registering(JacocoCoverageVerification::class) {
    dependsOn(jacocoTestReport)
    sourceDirectories.setFrom(files("${project.projectDir}/src/main/java"))
    classDirectories.setFrom(files(sessionClassesTree))
    executionData.setFrom(files(layout.buildDirectory.file("jacoco/testDebugUnitTest.exec")))

    violationRules {
        rule {
            element = "BUNDLE"
            limit {
                counter = "INSTRUCTION"
                value = "COVEREDRATIO"
                minimum = "0.90".toBigDecimal()
            }
            limit {
                counter = "LINE"
                value = "COVEREDRATIO"
                minimum = "0.90".toBigDecimal()
            }
        }
        rule {
            element = "CLASS"
            includes = listOf(
                "com.solo.purecast.core.session.TrackTransactionManager",
                "com.solo.purecast.core.session.TriStateTransitionMatrix"
            )
            limit {
                counter = "BRANCH"
                value = "COVEREDRATIO"
                minimum = "1.00".toBigDecimal()
            }
        }
    }
}

// 💡 Native C++ ASan / UBSan / TSan 宿主机状态机与 RAOP 协议栈测试门禁 (#7)
val runNativeSanitizerTests by tasks.registering(Exec::class) {
    group = "verification"
    description = "Configure, build, and run C++ ASan/UBSan/TSan host tests via CTest"
    workingDir = projectDir
    commandLine(
        "sh",
        "-c",
        """
        export PATH="/opt/homebrew/bin:/usr/local/bin:${'$'}PATH"
        cmake -S src/main/cpp/tests -B src/main/cpp/tests/build &&
        cmake --build src/main/cpp/tests/build -j8 &&
        ctest --test-dir src/main/cpp/tests/build --output-on-failure --timeout 20 --repeat until-fail:2
        """.trimIndent()
    )
}

tasks.named("check") {
    dependsOn(jacocoTestCoverageVerification)
    dependsOn(runNativeSanitizerTests)
}

val isOfficialCiRelease = System.getenv("CI") == "true" &&
    System.getenv("GITHUB_EVENT_NAME") == "release"

tasks.matching {
    it.name.endsWith("Release") &&
        (it.name.startsWith("assemble") || it.name.startsWith("bundle") || it.name.startsWith("package"))
}.configureEach {
    doFirst {
        if (isOfficialCiRelease && !hasReleaseSigning) {
            throw org.gradle.api.GradleException(
                "正式 GitHub Release 构建缺少签名配置 (RELEASE_KEYSTORE_BASE64 / RELEASE_STORE_PASSWORD / RELEASE_KEY_ALIAS / RELEASE_KEY_PASSWORD)，禁止静默回退 debug 签名"
            )
        }
    }
}
