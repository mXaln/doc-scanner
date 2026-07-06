plugins {
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.androidMultiplatformLibrary)
    alias(libs.plugins.composeMultiplatform)
    alias(libs.plugins.composeCompiler)
    alias(libs.plugins.sqlDelight)
    alias(libs.plugins.kotlinSerialization)
}

kotlin {
    jvmToolchain(17)

    android {
        namespace = "org.bibletranslationtools.docscanner.shared"
        compileSdk = libs.versions.android.compileSdk.get().toInt()
        minSdk = libs.versions.android.minSdk.get().toInt()

        androidResources {
            enable = true
        }
    }

    listOf(
        iosArm64(),
        iosSimulatorArm64()
    ).forEach { iosTarget ->
        iosTarget.binaries.framework {
            baseName = "Shared"
            isStatic = true
        }
    }

    sourceSets {
        androidMain.dependencies {
            implementation(libs.compose.uiToolingPreview)

            implementation(libs.koin.android)
            implementation(libs.sqldeight.android)
            implementation(libs.ktor.client.android)
            implementation(libs.slf4j.jvm)

            implementation(libs.androidx.preference.ktx)
            implementation(libs.kzip)
            implementation(libs.mlKit.documentScanner)
            implementation(libs.okhttp)
        }
        iosMain.dependencies {
            implementation(libs.ktor.client.darwin)
            implementation(libs.sqldelight.native.driver)
        }
        commonMain.dependencies {
            implementation(libs.multiplatform.settings)
            implementation(libs.multiplatform.settings.coroutines)
            implementation(libs.compose.runtime)
            implementation(libs.compose.foundation)
            implementation(libs.compose.material3)
            implementation(libs.compose.materialIconsExtended)
            implementation(libs.compose.ui)
            implementation(libs.compose.components.resources)
            implementation(libs.compose.components.uiToolingPreview)
            implementation(libs.androidx.lifecycle.viewmodel)
            implementation(libs.androidx.lifecycle.runtime.compose)

            api(libs.koin.core)
            implementation(libs.koin.compose)
            implementation(libs.koin.compose.viewmodel)

            implementation(libs.voyager.navigator)
            implementation(libs.voyager.screenmodel)
            implementation(libs.voyager.transitions)
            implementation(libs.voyager.koin)

            implementation(libs.sqldelight.coroutines)
            implementation(libs.kotlinx.datetime)
            implementation(libs.kotlinx.serialization.json)

            implementation(libs.kotlinx.io)
            implementation(libs.ktor.client.core)
            implementation(libs.ktor.client.content.negotiation)
            implementation(libs.ktor.client.serialization.json)

            implementation(libs.coil.compose)
            implementation(libs.kotlin.logging)

            implementation(libs.filekit.dialogs.core)
            implementation(libs.filekit.dialogs.compose)
        }
    }

    sqldelight {
        databases {
            create("MainDatabase") {
                packageName = "org.bibletranslationtools.database"
            }
        }
    }
}

compose.resources {
    packageOfResClass = "docscanner.composeapp.generated.resources"
}

val updateIosVersion = tasks.register("updateIosVersion") {
    description = """
        Sync the iOS Config.xcconfig version from libs.versions.toml so the app
        version stays aligned with Android. MARKETING_VERSION / CURRENT_PROJECT_VERSION
        drive Xcode's General > Identity, the build settings and Info.plist. Runs as
        part of any iOS framework build (Android Studio Gradle build or Xcode's
        embedAndSign phase).
    """.trimIndent()
    val versionName = libs.versions.app.versionName.get()
    val versionCode = libs.versions.app.versionCode.get()
    val xcconfigFile = rootProject.file("iosApp/Configuration/Config.xcconfig")
    inputs.property("versionName", versionName)
    inputs.property("versionCode", versionCode)
    outputs.file(xcconfigFile)
    doLast {
        var text = xcconfigFile.readText()
        text = text.replace(
            Regex("(?m)^MARKETING_VERSION=.*$"),
            "MARKETING_VERSION=$versionName"
        )
        text = text.replace(
            Regex("(?m)^CURRENT_PROJECT_VERSION=.*$"),
            "CURRENT_PROJECT_VERSION=$versionCode"
        )
        xcconfigFile.writeText(text)
        logger.lifecycle("Set iOS version $versionName ($versionCode) in $xcconfigFile")
    }
}

tasks.matching {
    it.name.startsWith("link") && it.name.contains("Framework") ||
        it.name.startsWith("embedAndSign")
}.configureEach {
    dependsOn(updateIosVersion)
}
