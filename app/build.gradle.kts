plugins {
    alias(libs.plugins.android.application)
}

android {
    buildFeatures.buildConfig = true
    namespace = "io.github.lujinxin.nextep"
    compileSdk {
        version = release(36) {
            minorApiLevel = 1
        }
    }

    defaultConfig {
        applicationId = "io.github.yishisanren.panes"
        minSdk = 35
        targetSdk = 36
        versionCode = 116
        versionName = "3.0.3"
        val touchDiagnostics = providers.gradleProperty("panesTouchDiagnostics").orNull == "true"
        buildConfigField("boolean", "TOUCH_DIAGNOSTICS", touchDiagnostics.toString())
        buildConfigField("String", "DIAGNOSTIC_BUILD", "\"${if (touchDiagnostics) "20261028-touch2" else "standard"}\"")
        if (touchDiagnostics) versionNameSuffix = "-touch2"

    }

    buildTypes {
        release {
            optimization {
                enable = false
            }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    packaging {
        resources.excludes += "/META-INF/{AL2.0,LGPL2.1}"
    }

    testOptions.unitTests.isIncludeAndroidResources = true
}

dependencies {
    // The framework provides this API at runtime; it must never be packaged in the APK.
    compileOnly("io.github.libxposed:api:102.0.0")

    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.core.ktx)
    implementation(libs.material)
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.robolectric:robolectric:4.17")
}

tasks.withType<org.gradle.api.tasks.testing.Test>().configureEach {
    // Robolectric's Android 16+ shared-memory setup uses the JDK file-descriptor accessor.
    jvmArgs("--add-exports=java.base/jdk.internal.access=ALL-UNNAMED")
}
