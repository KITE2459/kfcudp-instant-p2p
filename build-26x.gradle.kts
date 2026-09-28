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

    // 디버그: ./gradlew ":<버전>:runClient" -PfakeRooms=84 → 방 목록에 가짜 방 84개(PublicRoomBrowser.FAKE_ROOMS)
    findProperty("fakeRooms")?.let { n -> runs.named("client") { vmArg("-Dkfcudp.debug.fakeRooms=$n") } }
    // 개발 클라이언트 닉네임 고정(-PdevName=KiteDev) — 기본은 실행마다 PlayerNNN 이라 서버의 정품 인증 예외 목록에 못 넣는다.
    findProperty("devName")?.let { n -> runs.named("client") { programArgs("--username", n.toString()) } }
}

dependencies {
    minecraft("com.mojang:minecraft:${stonecutter.current.version}")
    implementation("net.fabricmc:fabric-loader:${project.property("loader_version_26x")}")
    implementation("net.fabricmc.fabric-api:fabric-api:${sc.properties["deps.fabric_api"] as String}")

    // QUIC — 순수 자바(kwik). 네이티브 없음, 전부 합쳐 646KB.
    // agent15는 kwik의 TLS 1.3 구현 — QUIC은 레코드 프레이밍 없는 핸드셰이크 바이트가
    // 필요해서 JDK SSLEngine을 쓸 수 없다. 대량 암복호화는 JCE(AES-NI)를 탄다.
    implementation("tech.kwik:kwik:0.11")
}

tasks.processResources {
    val modVersion = project.version
    val loaderDepends = ">=${project.property("loader_version_26x")}"
    // jar마다 자기 버전에만 설치되게 고정 — 범위로 두면 다른 버전용 jar가 로드돼 믹스인/API 불일치로 튕긴다.
    val minecraftDepends = stonecutter.current.version
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

    // kwik(QUIC) 클래스를 jar 에 번들링 — 네이티브는 없다
    from({
        // runtimeClasspath 를 봐야 한다 — kwik POM 이 agent15/hkdf/siphash 를 runtime 스코프로
        // 선언하므로 compileClasspath 에는 안 올라오고, 그러면 게임에서 TlsStatusEventHandler
        // NoClassDefFoundError 가 난다. 마인크래프트/Fabric 은 아래 그룹 필터가 걸러낸다.
        configurations.runtimeClasspath.get().resolvedConfiguration.resolvedArtifacts
            .filter {
                it.moduleVersion.id.group in setOf(
                    // kwik + 그 의존(agent15 = TLS 1.3, hkdf, siphash). 전부 순수 자바 —
                    // libwebrtc 를 걷어내면서 네이티브 라이브러리가 하나도 없어졌다.
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
