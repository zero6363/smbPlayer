import java.util.Properties
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

// ABI 变体：-PmpvAbi=arm64（默认，精简）或 -PmpvAbi=all（全架构，模拟器可装）
val abiVariant = (findProperty("mpvAbi") as? String) ?: "arm64"

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// 统一签名：D:\code\sdk\signing 下的固定 keystore，所有 app 通用。
// 存在则 release 用它，不存在则回退 debug 签名。
val sdkSigningDir = file("D:/code/sdk/signing")
val sdkKeystore = sdkSigningDir.resolve("android-release.keystore")
val sdkSigningProps = sdkSigningDir.resolve("signing.properties")
    .takeIf { it.exists() }
    ?.inputStream()
    ?.use { Properties().apply { load(it) } }

android {
    namespace = "com.smbplayer.app"
    compileSdk = 36

    defaultConfig {
        // [mpv 实验分支] 改包名以便与原版共存测试
        applicationId = "com.smbplayer.app.mpv"
        minSdk = 26
        targetSdk = 35
        versionCode = 52
        versionName = "1.42"

        // [mpv 实验分支] ABI 由 -PmpvAbi 控制，默认 arm64 精简包：
        //   assembleRelease -PmpvAbi=arm64 → 仅 arm64-v8a（约 46MB，真机/发布）
        //   assembleRelease -PmpvAbi=all   → 全架构（约 113MB，模拟器可装）
        ndk {
            abiFilters += when (abiVariant) {
                "all" -> listOf("arm64-v8a", "armeabi-v7a", "x86", "x86_64")
                else -> listOf("arm64-v8a")
            }
        }
    }

    signingConfigs {
        if (sdkKeystore.exists() && sdkSigningProps != null) {
            create("release") {
                storeFile = sdkKeystore
                storePassword = sdkSigningProps.getProperty("storepass")
                keyAlias = sdkSigningProps.getProperty("alias")
                keyPassword = sdkSigningProps.getProperty("keypass")
            }
        }
    }

    buildTypes {
        debug {
            isMinifyEnabled = false
        }
        release {
            isMinifyEnabled = false
            isShrinkResources = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            signingConfig = signingConfigs.findByName("release")
                ?: signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        viewBinding = true
    }

    lint {
        abortOnError = false
        checkReleaseBuilds = false
    }

    packaging {
        resources {
            excludes += setOf(
                "META-INF/DEPENDENCIES",
                "META-INF/LICENSE",
                "META-INF/LICENSE.txt",
                "META-INF/license.txt",
                "META-INF/NOTICE",
                "META-INF/NOTICE.txt",
                "META-INF/notice.txt",
                "META-INF/ASL2.0"
            )
        }
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.recyclerview:recyclerview:1.3.2")
    implementation("androidx.swiperefreshlayout:swiperefreshlayout:1.1.0")
    implementation("androidx.viewpager2:viewpager2:1.1.0")
    implementation("androidx.constraintlayout:constraintlayout:2.1.4")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.6")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")

    // 播放器
    implementation("androidx.media3:media3-exoplayer:1.4.1")
    implementation("androidx.media3:media3-ui:1.4.1")

    // [mpv 实验分支] 本地 libmpv AAR（46MB，GPL-3.0）
    implementation(files("libs/libmpv-1.0.0.aar"))

    // SMB 客户端（smbj：主引擎，目录浏览/播放/删除）
    implementation("com.hierynomus:smbj:0.15.0")

    // jcifs-ng：仅用于真实共享枚举（SmbFile 列 smb://host/ 根即共享列表）与局域网发现。
    // smbj 不提供共享枚举 API，只能靠候选表逐个试连，共享名不在表里就「扫不到」。
    // bcprov 为 SMB3 加密协商所需（jcifs 声明为 optional，需显式引入）。
    implementation("eu.agno3.jcifs:jcifs-ng:2.1.10")
    implementation("org.bouncycastle:bcprov-jdk18on:1.78.1")

    // 凭据加密存储
    implementation("androidx.security:security-crypto:1.1.0-alpha06")
}
