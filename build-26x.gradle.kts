// 26.x(언옵퓨스케이티드) 전용 빌드스크립트 — settings.gradle.kts의 .buildscript()로 지정됨.
// 1.21.x용 build.gradle.kts와 갈라지는 지점:
//   · 플러그인이 net.fabricmc.fabric-loom-remap → net.fabricmc.fabric-loom(리매핑 없음)
//   · mappings(...) 선언 자체가 없음 — Mojang 공식 매핑이 Minecraft 아티팩트에 그대로 실려 있음
//   · modImplementation → implementation (리매핑 대상이 없으므로 일반 의존성과 동일)
//   · Java 25, Fabric Loader 0.19.5+
//   · mixin { useLegacyMixinAp = true }가 불필요 — 그 설정은 TinyRemapper의 인라인
//     리매핑이 우리 @Inject full-descriptor 타겟을 깨는 문제를 우회하려던 것이었는데,
//     26.x는 애초에 리매핑을 안 하므로 그 문제 자체가 없다.
plugins {
    id("net.fabricmc.fabric-loom") version "1.17-SNAPSHOT"
    id("maven-publish")
}

version = property("mod.version") as String
group = property("mod.group") as String
base.archivesName = "instant-p2p-${stonecutter.current.version}"

repositories {
    mavenCentral()
}

loom {
    splitEnvironmentSourceSets()

    mods {
        create("instant-p2p") {
            sourceSet(sourceSets["main"])
            sourceSet(sourceSets["client"])
        }
    }
}

dependencies {
    minecraft("com.mojang:minecraft:${stonecutter.current.version}")
    implementation("net.fabricmc:fabric-loader:${project.property("loader_version_26x")}")
    implementation("net.fabricmc.fabric-api:fabric-api:${sc.properties["deps.fabric_api"] as String}")

    // WebRTC Java — dev.onvoid.webrtc 0.14.0 (MC 버전과 무관, 고정)
    implementation("dev.onvoid.webrtc:webrtc-java:0.14.0")
    implementation("dev.onvoid.webrtc:webrtc-java:0.14.0:windows-x86_64")
    implementation("dev.onvoid.webrtc:webrtc-java:0.14.0:linux-x86_64")
    implementation("dev.onvoid.webrtc:webrtc-java:0.14.0:macos-x86_64")
    implementation("dev.onvoid.webrtc:webrtc-java:0.14.0:macos-aarch64")
}

tasks.processResources {
    val modVersion = project.version
    val loaderDepends = ">=${project.property("loader_version_26x")}"
    val minecraftDepends = ">=26.1 <=26.2"
    val javaDepends = ">=25"
    inputs.property("version", modVersion)
    inputs.property("loaderDepends", loaderDepends)
    filesMatching("fabric.mod.json") {
        expand(
            "version" to modVersion,
            "loaderDepends" to loaderDepends,
            "minecraftDepends" to minecraftDepends,
            "javaDepends" to javaDepends,
        )
    }
}

tasks.withType<JavaCompile>().configureEach {
    options.release = 25
}

java {
    withSourcesJar()
    sourceCompatibility = JavaVersion.VERSION_25
    targetCompatibility = JavaVersion.VERSION_25
}

tasks.jar {
    val projectName = project.name
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
    inputs.property("projectName", projectName)

    // webrtc-java 클래스 및 네이티브 라이브러리를 jar에 번들링
    from({
        configurations.compileClasspath.get().resolvedConfiguration.resolvedArtifacts
            .filter { it.moduleVersion.id.group in setOf("dev.onvoid.webrtc") }
            .map { zipTree(it.file) }
    }) {
        exclude("META-INF/*.SF")
        exclude("META-INF/*.DSA")
        exclude("META-INF/*.RSA")
        exclude("META-INF/MANIFEST.MF")
    }

    from(rootProject.file("LICENSE")) {
        rename { "${it}_$projectName" }
    }
}

publishing {
    publications {
        create<MavenPublication>("mavenJava") {
            from(components["java"])
        }
    }
    repositories {}
}
