import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import java.io.File

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

/**
 * Release APK'sinin telefona DOGRUDAN kurulabilmesi icin debug keystore ile imzalanir.
 * Oncelik sirasi:
 *   1) repo icindeki keystore/debug.keystore  (CI'da ve baska makinede tekrarlanabilir olsun diye)
 *   2) ~/.android/debug.keystore              (Android Studio'nun urettigi standart keystore)
 *   3) yoksa keytool ile otomatik uretilir
 * Not: Bu bir "uretim" imzasi degildir; Play Store'a yuklenemez, sadece yan yukleme icindir.
 */
val debugKeystore: File = run {
    val inRepo = rootProject.file("keystore/debug.keystore")
    val userWide = File(System.getProperty("user.home"), ".android/debug.keystore")
    when {
        inRepo.exists() -> inRepo
        userWide.exists() -> userWide
        else -> {
            inRepo.parentFile.mkdirs()
            val keytool = File(System.getProperty("java.home"), "bin/keytool").absolutePath
            val result = providers.exec {
                commandLine(
                    keytool, "-genkeypair", "-v",
                    "-keystore", inRepo.absolutePath,
                    "-storepass", "android",
                    "-keypass", "android",
                    "-alias", "androiddebugkey",
                    "-keyalg", "RSA",
                    "-keysize", "2048",
                    "-validity", "10000",
                    "-dname", "CN=Android Debug,O=Android,C=US"
                )
            }
            // Konfigurasyon aninda calistir; basarisiz olursa build burada anlamli bir hata verir.
            result.result.get().assertNormalExitValue()
            inRepo
        }
    }
}

android {
    namespace = "com.noluryard.autoclicker"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.noluryard.autoclicker"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "1.0.0"
        resourceConfigurations += setOf("tr", "en")
    }

    signingConfigs {
        create("sideload") {
            storeFile = debugKeystore
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
    }

    buildTypes {
        debug {
            signingConfig = signingConfigs.getByName("sideload")
        }
        release {
            // ProGuard/R8 kapali: Compose + AccessibilityService reflection'lari ile
            // ugrasmadan dogrudan kurulabilir bir APK cikmasi isteniyor.
            isMinifyEnabled = false
            isShrinkResources = false
            signingConfig = signingConfigs.getByName("sideload")
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.datastore.preferences)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.google.material)

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons.extended)
    debugImplementation(libs.androidx.compose.ui.tooling)
}

/**
 * assembleRelease sonrasi APK'yi proje kokundeki out/autoclicker.apk konumuna kopyalar.
 */
val copyApkToOut by tasks.registering(Copy::class) {
    from(layout.buildDirectory.dir("outputs/apk/release")) {
        include("*.apk")
    }
    into(rootProject.layout.projectDirectory.dir("out"))
    rename { "autoclicker.apk" }
    doLast {
        logger.lifecycle("APK -> ${rootProject.file("out/autoclicker.apk").absolutePath}")
    }
}

tasks.named("assembleRelease") {
    finalizedBy(copyApkToOut)
}
