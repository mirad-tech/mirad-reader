import org.gradle.api.file.DirectoryProperty
import org.gradle.api.tasks.InputDirectory
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
}

/** 保留一个脚本、一个闭包；按源码入口的固定顺序拼接，不增加运行时加载步骤。 */
abstract class ReaderAssetsTask : DefaultTask() {
    @get:InputDirectory
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val sourceDirectory: DirectoryProperty

    @get:OutputDirectory
    abstract val outputDirectory: DirectoryProperty

    @TaskAction
    fun bundle() {
        val source = sourceDirectory.get().asFile
        fun read(name: String) = source.resolve(name).readText().replace("\r\n", "\n")
        val script = Regex("(?m)^    // @include (\\S+)\n")
            .replace(read("reader.js")) { read(it.groupValues[1]) }
        outputDirectory.file("reader/reader.js").get().asFile.apply {
            parentFile.mkdirs()
            writeText(script)
        }
    }
}

androidComponents.onVariants { variant ->
    val bundle = tasks.register<ReaderAssetsTask>("bundle${variant.name.replaceFirstChar { it.uppercaseChar() }}Reader") {
        sourceDirectory.set(layout.projectDirectory.dir("src/main/reader"))
        outputDirectory.set(layout.buildDirectory.dir("generated/readerAssets/${variant.name}"))
    }
    variant.sources.assets?.addGeneratedSourceDirectory(bundle, ReaderAssetsTask::outputDirectory)
}

ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
}

android {
    namespace = "com.mirad.reader"
    compileSdk {
        version = release(36) {
            minorApiLevel = 1
        }
    }

    defaultConfig {
        applicationId = "com.mirad.reader"
        minSdk = 26
        targetSdk = 36
        versionCode = 23
        versionName = "1.1.4"
    }

    buildTypes {
        debug {
            isMinifyEnabled = false
        }
        create("preview") {
            initWith(getByName("debug"))
            applicationIdSuffix = ".preview"
            matchingFallbacks += "debug"
        }
        release {
            // 之前 release 与 debug 一样不混淆不压缩：正式包体积偏大、符号全暴露。
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    packaging {
        resources {
            excludes += setOf(
                "/META-INF/{AL2.0,LGPL2.1}",
                "/META-INF/DEPENDENCIES",
                "/META-INF/LICENSE*",
                "/META-INF/NOTICE*",
                "META-INF/*.kotlin_module"
            )
        }
    }

    testOptions {
        unitTests {
            isReturnDefaultValues = true
        }
    }
}

dependencies {
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.documentfile)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    implementation(libs.androidx.webkit)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.juniversalchardet)
    ksp(libs.androidx.room.compiler)
    debugImplementation(libs.androidx.compose.ui.tooling)
    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
}
