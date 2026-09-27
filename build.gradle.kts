plugins {
    id("net.fabricmc.fabric-loom-remap") version "1.16-SNAPSHOT"
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

    // Loom 1.12+ defaults to remapping Mixins in-place via TinyRemapper instead of the
    // classic AP-generated refmap. That path doesn't correctly remap our full-descriptor
    // @Inject targets (e.g. ClientConnectionMixin's overload-disambiguating `connect(...)`
    // selector), which silently produces a jar with no refmap and a runtime
    // "No refMap loaded" MixinApplyError. Force the legacy AP/refmap path instead.
    mixin {
        useLegacyMixinAp = true
    }

    mods {
        create("instant-p2p") {
            sourceSet(sourceSets["main"])
            sourceSet(sourceSets["client"])
        }
    }

    // 디버그: ./gradlew ":<버전>:runClient" -PfakeRooms=84 → 방 목록에 가짜 방 84개(PublicRoomBrowser.FAKE_ROOMS)
    findProperty("fakeRooms")?.let { n -> runs.named("client") { vmArg("-Dkfcudp.debug.fakeRooms=$n") } }
}

dependencies {
    minecraft("com.mojang:minecraft:${stonecutter.current.version}")
    mappings("net.fabricmc:yarn:${sc.properties["deps.yarn"] as String}:v2")
    modImplementation("net.fabricmc:fabric-loader:${property("loader_version")}")
    modImplementation("net.fabricmc.fabric-api:fabric-api:${sc.properties["deps.fabric_api"] as String}")

    // WebRTC Java — dev.onvoid.webrtc 0.14.0 (MC 버전과 무관, 고정)
    implementation("dev.onvoid.webrtc:webrtc-java:0.14.0")
    implementation("dev.onvoid.webrtc:webrtc-java:0.14.0:windows-x86_64")
    implementation("dev.onvoid.webrtc:webrtc-java:0.14.0:linux-x86_64")
    implementation("dev.onvoid.webrtc:webrtc-java:0.14.0:macos-x86_64")
    implementation("dev.onvoid.webrtc:webrtc-java:0.14.0:macos-aarch64")

    // QUIC — 순수 자바(kwik). 네이티브 없음, 전부 합쳐 646KB.
    // agent15는 kwik의 TLS 1.3 구현 — QUIC은 레코드 프레이밍 없는 핸드셰이크 바이트가
    // 필요해서 JDK SSLEngine을 쓸 수 없다. 대량 암복호화는 JCE(AES-NI)를 탄다.
    implementation("tech.kwik:kwik:0.11")
}

tasks.processResources {
    val modVersion = project.version
    val loaderDepends = ">=${project.property("loader_version")}"
    // jar마다 자기 버전에만 설치되게 고정 — 범위로 두면 다른 버전용 jar가 로드돼 믹스인/API 불일치로 튕긴다.
    val minecraftDepends = stonecutter.current.version
    val javaDepends = ">=21"
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
    options.release = 21
}

java {
    withSourcesJar()
    sourceCompatibility = JavaVersion.VERSION_21
    targetCompatibility = JavaVersion.VERSION_21
}

tasks.jar {
    val projectName = project.name
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
    inputs.property("projectName", projectName)

    // webrtc-java 클래스 및 네이티브 라이브러리를 jar에 번들링
    from({
        // runtimeClasspath 를 봐야 한다 — kwik POM 이 agent15/hkdf/siphash 를 runtime 스코프로
        // 선언하므로 compileClasspath 에는 안 올라오고, 그러면 게임에서 TlsStatusEventHandler
        // NoClassDefFoundError 가 난다. 마인크래프트/Fabric 은 아래 그룹 필터가 걸러낸다.
        configurations.runtimeClasspath.get().resolvedConfiguration.resolvedArtifacts
            .filter {
                it.moduleVersion.id.group in setOf(
                    "dev.onvoid.webrtc",
                    // kwik + 그 의존(agent15 = TLS 1.3, hkdf, siphash)
                    // ponytail: LGPL-3.0이라 배포판에서는 shading 대신 JiJ(중첩 jar)로 옮겨야 한다
                    "tech.kwik", "at.favre.lib", "com.io7m.repackage.io.whitfin",
                )
            }
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
