plugins { `java-library` }
val androidJar = rootProject.extra["androidJar"] as Provider<File>
val d8Jar = rootProject.extra["d8Jar"] as Provider<File>
dependencies { compileOnly(files(androidJar)) }
java { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
tasks.withType<JavaCompile>().configureEach { options.encoding = "UTF-8" }
val dexDir = layout.buildDirectory.dir("dex")
val dexExtension by tasks.registering(JavaExec::class) {
    dependsOn(tasks.jar)
    classpath = files(d8Jar)
    mainClass.set("com.android.tools.r8.D8")
    inputs.file(tasks.jar.flatMap { it.archiveFile })
    outputs.dir(dexDir)
    doFirst {
        dexDir.get().asFile.mkdirs()
        args("--release", "--min-api", "26", "--lib", androidJar.get().absolutePath,
             "--output", dexDir.get().asFile.absolutePath, tasks.jar.get().archiveFile.get().asFile.absolutePath)
    }
}
val extensionArtifact by configurations.creating { isCanBeConsumed = true; isCanBeResolved = false }
artifacts { add(extensionArtifact.name, dexDir.map { it.file("classes.dex") }) { builtBy(dexExtension) } }
