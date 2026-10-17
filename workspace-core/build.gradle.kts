import com.vanniktech.maven.publish.JavadocJar
import com.vanniktech.maven.publish.KotlinJvm

plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.maven.publish)
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}
kotlin { jvmToolchain(17) }

dependencies {
    implementation(libs.coroutines.core)
    // 仅用 Json.parseToJsonElement 做 JSON 预览美化（不用 @Serializable，无需编译插件）
    implementation(libs.serialization.json)
    testImplementation(libs.junit)
    testImplementation(libs.coroutines.test)
}

// composite build 依赖替换：宿主 includeBuild 时按 group:name 自动替换远端坐标
group = "com.apex.workspace"
version = "0.1.0"

mavenPublishing {
    configure(KotlinJvm(javadocJar = JavadocJar.None(), sourcesJar = true))
    coordinates(
        groupId = "com.apex.workspace",
        artifactId = "workspace-core",
        version = "0.1.0",
    )
    pom {
        name.set("Apex Workspace Kit :: Core")
        description.set("Pure-Kotlin workspace engine: git status/diff/log parsing, file tree, multi-format preview model, artifact scanning, polling watcher and explorer controller (zero Android deps)")
        url.set("https://github.com/Ultra-Guru/apex-workspace-kit")
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
            connection.set("scm:git:git@github.com:Ultra-Guru/apex-workspace-kit.git")
            url.set("https://github.com/Ultra-Guru/apex-workspace-kit")
        }
    }
}
