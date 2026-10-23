import com.vanniktech.maven.publish.AndroidSingleVariantLibrary
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.maven.publish)
}

android {
    namespace = "com.apex.workspace.explorer"
    // 宿主 compileSdk=36；本地工具链只装了 platforms;android-36，与宿主严格对齐
    compileSdk = 36
    defaultConfig {
        minSdk = 26
        consumerProguardFiles("consumer-rules.pro")
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildFeatures {
        compose = true
        // 强制所有资源加 workspace_ 前缀，防止与宿主 R 类冲突（镜像 browser-kit F2）
        resourcePrefix = "workspace_"
    }
    testOptions {
        unitTests.isReturnDefaultValues = true
    }
}

// KGP 2.2：kotlinOptions DSL 已 deprecation（KGP 3.0 移除）—— 项目级 compilerOptions。
kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

dependencies {
    // explorer 公开 API 暴露 core 类型（ExplorerUiState / WorkspaceExplorerController 等）
    api(project(":workspace-core"))
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.foundation)
    implementation(libs.compose.material3)
    implementation(libs.compose.material.icons)
    implementation(libs.compose.ui.tooling.preview)
    // 图片预览（与宿主 app 同版 coil 2.6.0；coil-svg 供 SVG 渲染）
    implementation(libs.coil.compose)
    implementation(libs.coil.svg)
    implementation(libs.lifecycle.compose)
    // BackHandler：与宿主 app 同版 1.9.3（composite build 避免双版本；不在根 catalog，故字面坐标）
    implementation("androidx.activity:activity-compose:1.9.3")
    // 预览 IO（PDF/SVG 源码读取）显式声明，不依赖 compose 传递
    implementation(libs.coroutines.core)
    testImplementation(libs.junit)
    testImplementation(libs.coroutines.test)
}

// composite build 依赖替换：宿主 includeBuild 时按 group:name 自动替换远端坐标
group = "com.apex.workspace"
version = "0.1.0"

mavenPublishing {
    // Android 单变体发布：release variant + sources；不发 javadoc（Dokka 在受限环境不稳）
    configure(
        AndroidSingleVariantLibrary(
            variant = "release",
            sourcesJar = true,
            publishJavadocJar = false,
        ),
    )
    coordinates(
        groupId = "com.apex.workspace",
        artifactId = "workspace-explorer",
        version = "0.1.0",
    )
    pom {
        name.set("Apex Kit Workspace :: Explorer")
        description.set("Compose UI for the workspace explorer page: folder tree, multi-format preview, git panel, changes & artifacts with live agent follow")
        url.set("https://github.com/Ultra-Guru/apex-kit-workspace")
        licenses {
            license {
                name.set("MIT")
                url.set("https://opensource.org/licenses/MIT")
            }
        }
        developers {
            developer { id.set("Ultra-Guru") }
        }
        scm {
            connection.set("scm:git:git@github.com:Ultra-Guru/apex-kit-workspace.git")
            url.set("https://github.com/Ultra-Guru/apex-kit-workspace")
        }
    }
}
