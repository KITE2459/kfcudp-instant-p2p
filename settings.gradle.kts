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
        // Era 1: 옵퓨스케이티드 + Yarn 매핑 구간. 26.x(언옵퓨스케이티드/Mojang 매핑)는 별도 단계.
        // versions(a, b)는 범위가 아니라 명시된 버전만 노드로 만든다 — 중간 패치를 다 나열해야 함.
        versions("1.21.5", "1.21.6", "1.21.7", "1.21.8", "1.21.9", "1.21.10", "1.21.11")
        vcsVersion = "1.21.5"
    }
}

rootProject.name = "kfcudp-instant-p2p"
