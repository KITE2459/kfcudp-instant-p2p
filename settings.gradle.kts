pluginManagement {
    repositories {
        mavenCentral()
        gradlePluginPortal()
        maven("https://maven.fabricmc.net/")
    }
}

plugins {
    id("dev.kikugie.stonecutter") version "0.9.8"
}

stonecutter {
    create(rootProject) {
        // Era 1: 옵퓨스케이티드 + Yarn 매핑 구간 (기본 build.gradle.kts 사용).
        // versions(a, b)는 범위가 아니라 명시된 버전만 노드로 만든다 — 중간 패치를 다 나열해야 함.
        versions("1.21.5", "1.21.6", "1.21.7", "1.21.8", "1.21.9", "1.21.10", "1.21.11")

        // Era 2: 26.1부터 Minecraft가 완전히 언옵퓨스케이티드로 바뀌어 Yarn 자체가 없어졌다.
        // Loom 플러그인도 net.fabricmc.fabric-loom-remap → net.fabricmc.fabric-loom(리매핑 없음)으로
        // 교체되고, Mojang 공식 매핑을 그대로 쓴다 — 완전히 다른 빌드 로직이 필요해서 별도
        // 빌드스크립트로 분리한다(Stonecutter의 .buildscript() 기능).
        versions("26.1", "26.1.1", "26.1.2", "26.2")
            .buildscript("build-26x.gradle.kts")

        vcsVersion = "1.21.5"
    }
}

rootProject.name = "kfcudp-instant-p2p"
