@file:OptIn(ExperimentalWasmDsl::class)

import org.jetbrains.kotlin.gradle.ExperimentalWasmDsl
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.androidKotlinMultiplatformLibrary)
    alias(libs.plugins.dokka)
    alias(libs.plugins.vanniktech.mavenPublish)
    alias(libs.plugins.composeCompiler)
    alias(libs.plugins.composeMultiplatform)
    alias(libs.plugins.kotlinSerialization)
}

group = "io.github.crowded-libs"
version = "0.3.1"

kotlin {
    android {
        namespace = "io.github.crowdedlibs.duks.routing"
        compileSdk = libs.versions.android.compileSdk.get().toInt()
        minSdk = libs.versions.android.minSdk.get().toInt()
        compilerOptions {
            jvmTarget.set(JvmTarget.JVM_11)
        }
        withHostTest {}
    }
    jvm()
    iosArm64()
    iosSimulatorArm64()
    wasmJs {
        browser()
    }

    sourceSets {
        all {
            languageSettings.apply {
                optIn("kotlinx.coroutines.ExperimentalCoroutinesApi")
            }
        }
        commonMain {
            dependencies {
                implementation(libs.compose.runtime)
                implementation(libs.compose.foundation)
                implementation(libs.compose.ui)
                implementation(libs.duks)
                implementation(libs.kotlinx.coroutines.core)
                implementation(libs.kotlinx.serialization)
                implementation(libs.kotlinx.serialization.json)
            }
        }

        commonTest {
            dependencies {
                implementation(libs.kotlin.test)
                implementation(libs.kotlinx.coroutines.test)
                implementation(libs.kotlinx.serialization.json)
            }
        }

        wasmJsMain {
            languageSettings.apply {
                optIn("kotlin.js.ExperimentalWasmJsInterop")
            }
        }
    }
}

dokka {
    moduleName = project.name
    dokkaSourceSets {
        named("commonMain")
    }
}

val dokkaHtmlJar = tasks.register<Jar>("dokkaHtmlJar") {
    description = "A HTML Documentation JAR containing Dokka HTML"
    from(tasks.dokkaGeneratePublicationHtml.flatMap { it.outputDirectory })
    archiveClassifier.set("html-doc")
}

mavenPublishing {
    publishToMavenCentral()

    signAllPublications()

    coordinates(group.toString(), "duks-routing", version.toString())

    pom {
        name = project.name
        description = "A routing library for Compose built on duks for Kotlin Multiplatform Compose"
        inceptionYear = "2025"
        url = "https://github.com/crowded-libs/duks-routing/"
        licenses {
            license {
                name = "Apache 2.0"
                url = "https://www.apache.org/licenses/LICENSE-2.0.txt"
                distribution = "repo"
            }
        }
        developers {
            developer {
                id = "coreykaylor"
                name = "Corey Kaylor"
                email = "corey@kaylors.net"
            }
        }
        scm {
            url = "https://github.com/crowded-libs/duks-routing/"
            connection = "scm:git:git://github.com/crowded-libs/duks-routing.git"
            developerConnection = "scm:git:ssh://git@github.com/crowded-libs/duks-routing.git"
        }
    }
}
