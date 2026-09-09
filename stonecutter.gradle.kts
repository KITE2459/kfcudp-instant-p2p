plugins {
    id("dev.kikugie.stonecutter")
}

stonecutter active "1.21.5"

/** 각 버전 서브프로젝트의 build/libs/ 에 흩어진 jar를 versions/all/ 로 모은다. */
val collectAllVersionJars by tasks.registering {
    val collectDir = rootProject.layout.projectDirectory.dir("versions/all")
    stonecutter.versions.forEach { v -> dependsOn("${v.project}:build") }

    doLast {
        val out = collectDir.asFile
        out.mkdirs()
        stonecutter.versions.forEach { v ->
            val libs = project(":${v.project}").layout.buildDirectory.dir("libs").get().asFile
            libs.listFiles { f -> f.name.endsWith(".jar") && !f.name.endsWith("-sources.jar") }
                ?.forEach { it.copyTo(out.resolve(it.name), overwrite = true) }
        }
        println("모은 jar: ${out.absolutePath}")
    }
}

tasks.register("buildAllVersions") {
    group = "build"
    description = "모든 Stonecutter 버전을 빌드하고 versions/all/ 에 jar를 모은다."
    dependsOn(collectAllVersionJars)
}

/**
 * 루트 프로젝트 자체엔 원래 build 태스크가 없다(Loom이 각 버전 서브프로젝트에만
 * 적용됨). 그런데 Gradle은 태스크를 경로 없이 이름만("build")으로 실행하면 그
 * 이름을 가진 태스크를 하위 트리 전체에서 찾아 실행한다 — 그래서 IntelliJ Gradle
 * 도구창의 "kfc-udplib [build]"(=경로 $PROJECT_DIR$, 태스크명 build)를 눌러도
 * 실제로는 7개 버전 build가 전부 돌아가는데, 결과는 각자 자기 build/libs/에만
 * 남고 모아주는 로직을 안 탔다. 루트에 이 이름으로 태스크를 만들어 같은 이름
 * 매칭에 걸리게 하고, 끝나면 자동으로 모으게 한다.
 * ":1.21.5:build"처럼 경로를 직접 지정하는 day-to-day 빌드는 이 태스크와 무관하게
 * 그 서브프로젝트만 빌드된다 — 영향 없음.
 */
tasks.register("build") {
    group = "build"
    stonecutter.versions.forEach { v -> dependsOn("${v.project}:build") }
    finalizedBy(collectAllVersionJars)
}
