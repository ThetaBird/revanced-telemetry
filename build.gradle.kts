plugins { kotlin("jvm") version "2.3.10" apply false }
allprojects { version = "0.1.0"; group = "dev.selfhosted.music" }
val sdkDirectory = providers.environmentVariable("ANDROID_HOME")
    .orElse(providers.environmentVariable("ANDROID_SDK_ROOT"))
    .orElse(provider {
        val properties = java.util.Properties()
        val file = rootProject.file("local.properties")
        if (file.exists()) file.inputStream().use { properties.load(it) }
        properties.getProperty("sdk.dir", System.getProperty("user.home") + "/Library/Android/sdk")
    })
extra["androidJar"] = sdkDirectory.map { file("$it/platforms/android-36/android.jar") }
extra["d8Jar"] = provider { rootProject.file(".local/toolchain/r8-9.4.17.jar") }
