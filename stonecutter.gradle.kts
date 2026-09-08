plugins {
    id("dev.kikugie.stonecutter")
}

stonecutter active "1.21.5"

/** 지원하는 모든 버전을 한 번에 빌드해서 build/libs/all/ 에 모아준다. */
tasks.register("buildAllVersions") {
    group = "build"
    description = "모든 Stonecutter 버전을 빌드하고 build/libs/all/ 에 jar를 모은다."

    val collectDir = rootProject.layout.buildDirectory.dir("libs/all")

    stonecutter.versions.forEach { v ->
        val subproject = project(":${v.project}")
        dependsOn("${v.project}:build")
    }

    doLast {
        val out = collectDir.get().asFile
        out.mkdirs()
        stonecutter.versions.forEach { v ->
            val libs = project(":${v.project}").layout.buildDirectory.dir("libs").get().asFile
            libs.listFiles { f -> f.name.endsWith(".jar") && !f.name.endsWith("-sources.jar") }
                ?.forEach { it.copyTo(out.resolve(it.name), overwrite = true) }
        }
        println("모은 jar: ${out.absolutePath}")
    }
}
