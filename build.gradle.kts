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
}

tasks.processResources {
    val modVersion = project.version
    inputs.property("version", modVersion)
    filesMatching("fabric.mod.json") {
        expand("version" to modVersion)
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
