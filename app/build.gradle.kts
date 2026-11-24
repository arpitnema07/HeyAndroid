import org.gradle.testing.jacoco.plugins.JacocoTaskExtension
import org.gradle.testing.jacoco.tasks.JacocoReport

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ksp)
    alias(libs.plugins.hilt)
    jacoco
}

android {
    namespace = "com.example.heyandroid"
    compileSdk {
        version = release(37)
    }

    defaultConfig {
        applicationId = "com.example.heyandroid"
        minSdk = 29
        targetSdk = 37
        versionCode = 1
        versionName = "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        release {
            optimization {
                enable = true
                packageScope = setOf("androidx.**", "kotlin.**", "kotlinx.**")
            }
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    buildFeatures {
        compose = true
    }
    testOptions {
        unitTests {
            // Robolectric needs the app's resources, and the Compose UI tests run
            // on the JVM through it, so no phone is required for any of this.
            isIncludeAndroidResources = true
        }
    }
}

ksp {
    // Exported schemas are the reference the hand-written migrations are checked against.
    arg("room.schemaLocation", "$projectDir/schemas")
}

dependencies {
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)
    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)
    implementation(libs.androidx.hilt.work)
    ksp(libs.androidx.hilt.compiler)
    implementation(libs.androidx.work.runtime.ktx)
    implementation(libs.androidx.glance.appwidget)
    testImplementation(libs.junit)
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.core)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(platform(libs.androidx.compose.bom))
    testImplementation(libs.androidx.compose.ui.test.junit4)
    testImplementation(libs.androidx.compose.ui.test.manifest)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.junit)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
    debugImplementation(libs.androidx.compose.ui.tooling)
}

/**
 * Test coverage for the JVM suite. Unit, Robolectric integration and the Compose
 * UI tests all run in `testDebugUnitTest`, so one run covers all three layers:
 *
 *     ./gradlew coverageReport
 *
 * The HTML report lands in build/reports/jacoco/coverageReport/html/index.html
 * and the machine-readable one next to it as coverageReport.xml.
 */
jacoco {
    toolVersion = "0.8.13"
}

tasks.withType<Test>().configureEach {
    extensions.configure<JacocoTaskExtension> {
        // Robolectric loads classes from its own sandbox loader; without this the
        // agent sees them as having no code location and writes no coverage.
        isIncludeNoLocationClasses = true
        excludes = listOf("jdk.internal.*")
    }
}

val coverageReport by tasks.registering(JacocoReport::class) {
    group = "verification"
    description = "Tests the debug build and writes the coverage report."
    dependsOn("testDebugUnitTest")

    val classes = fileTree(layout.buildDirectory) {
        include("intermediates/built_in_kotlinc/debug/compileDebugKotlin/classes/**")
        include("tmp/kotlin-classes/debug/**")
        include("intermediates/javac/debug/compileDebugJavaWithJavac/classes/**")
        // Generated scaffolding is not code anyone writes or tests.
        exclude(
            "**/R.class",
            "**/R\$*.class",
            "**/BuildConfig.*",
            "**/Manifest*.*",
            "**/*_Factory.class",
            "**/*_Factory\$*.class",
            "**/*_MembersInjector.class",
            "**/*HiltModules*.class",
            "**/Hilt_*.class",
            "**/*_GeneratedInjector.class",
            "**/*_Impl.class",
            "**/dagger/**",
            "**/hilt_aggregated_deps/**",
            "**/com/example/heyandroid/HeyAndroidApp_HiltComponents*"
        )
    }
    classDirectories.setFrom(classes)
    sourceDirectories.setFrom(files("src/main/java"))
    executionData.setFrom(
        fileTree(layout.buildDirectory) {
            include("jacoco/*.exec")
        }
    )
    reports {
        xml.required.set(true)
        xml.outputLocation.set(layout.buildDirectory.file("reports/jacoco/coverageReport/coverageReport.xml"))
        html.required.set(true)
        html.outputLocation.set(layout.buildDirectory.dir("reports/jacoco/coverageReport/html"))
    }
}