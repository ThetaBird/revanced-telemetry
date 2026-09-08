plugins { kotlin("jvm") }
val cli = rootProject.file(".local/toolchain/revanced-cli-6.0.0-all.jar")
val extensionDex by configurations.creating
val androidJar = rootProject.extra["androidJar"] as Provider<File>
val d8Jar = rootProject.extra["d8Jar"] as Provider<File>
dependencies {
    compileOnly(files(cli))
    testImplementation(files(cli))
    testImplementation(kotlin("test-junit"))
    extensionDex(project(mapOf("path" to ":extensions:telemetry", "configuration" to "extensionArtifact")))
}
kotlin { compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17); freeCompilerArgs.addAll("-Xcontext-parameters", "-Xskip-prerelease-check") } }
java { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
tasks.jar {
    archiveBaseName.set("music-telemetry-jvm")
    from(extensionDex) { into("extensions"); rename { "telemetry.rve" } }
    manifest {
        attributes(
            "Name" to "Music telemetry",
            "Version" to project.version,
            "Description" to "Listen-compatible YouTube Music telemetry",
            "Source" to "https://github.com/ThetaBird/revanced-telemetry",
            "Author" to "ThetaBird",
            "Website" to "https://github.com/ThetaBird/revanced-telemetry",
            "License" to "GPL-3.0-only",
        )
    }
}
val dexDir = layout.buildDirectory.dir("patch-dex")
val dexPatches by tasks.registering(JavaExec::class) {
    dependsOn(tasks.jar)
    classpath = files(d8Jar)
    mainClass.set("com.android.tools.r8.D8")
    inputs.file(tasks.jar.flatMap { it.archiveFile })
    outputs.dir(dexDir)
    doFirst {
        dexDir.get().asFile.mkdirs()
        args("--release", "--min-api", "26", "--lib", androidJar.get().absolutePath,
             "--classpath", cli.absolutePath, "--output", dexDir.get().asFile.absolutePath,
             tasks.jar.get().archiveFile.get().asFile.absolutePath)
    }
}
val buildAndroid by tasks.registering(Jar::class) {
    dependsOn(dexPatches)
    archiveBaseName.set("music-telemetry")
    archiveExtension.set("rvp")
    from(tasks.jar.map { zipTree(it.archiveFile) })
    from(dexDir)
    manifest {
        attributes(
            "Name" to "Music telemetry",
            "Version" to project.version,
            "Description" to "Listen-compatible YouTube Music telemetry",
            "Source" to "https://github.com/ThetaBird/revanced-telemetry",
            "Author" to "ThetaBird",
            "Website" to "https://github.com/ThetaBird/revanced-telemetry",
            "License" to "GPL-3.0-only",
        )
    }
}
tasks.assemble { dependsOn(buildAndroid) }
