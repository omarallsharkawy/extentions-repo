@file:Suppress("ktlint:standard:kdoc")

pluginManagement {
    includeBuild("gradle/build-logic")
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
        maven(url = "https://www.jitpack.io")
    }
}

dependencyResolutionManagement {
    versionCatalogs {
        create("kei") {
            from(files("gradle/kei.versions.toml"))
        }
    }
    @Suppress("UnstableApiUsage")
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    @Suppress("UnstableApiUsage")
    repositories {
        google()
        mavenCentral()
        maven(url = "https://www.jitpack.io")
    }
}

enableFeaturePreview("TYPESAFE_PROJECT_ACCESSORS")

rootProject.name = "Yuzono-Anime"

val targetModules = listOf(
    "ar:aflamk1", "ar:anime4up", "ar:animerco", "ar:arabseed", "ar:arabshentai",
    "ar:arabx", "ar:arabxn", "ar:cimaleek", "ar:cimalight", "ar:egydead",
    "ar:faselhd", "ar:nxxhentai", "ar:okanime", "ar:sexalarab", "ar:sexmahali",
    "en:anikage", "en:animeparadise", "en:animetake", "en:hahomoe", "en:hanime", "en:hentaimama", "en:hexawatch",
    "en:hstream", "en:kayoanime", "en:kimoitv", "en:mapple", "en:myanime", "en:onetwothreeanime",
    "en:pinoymoviepedia", "en:rule34video", "en:uniquestream",
    "all:hentaitorrent", "all:jable", "all:javgg", "all:javguru", "all:missav", "all:nyaatorrent", "all:pornhub", "all:ptorrent",
    "all:rouvideo", "all:streamingcommunity", "all:supjav", "all:xnxx", "all:xvideos"
)

include(":core")

for (mod in targetModules) {
    include(":src:$mod")
}

// Load all modules under /lib that contain a build file
File(rootDir, "lib").listFiles()?.filter { 
    it.isDirectory && (File(it, "build.gradle").exists() || File(it, "build.gradle.kts").exists()) 
}?.forEach { include("lib:${it.name}") }

// Load all modules under /lib-multisrc that contain a build file
File(rootDir, "lib-multisrc").listFiles()?.filter { 
    it.isDirectory && (File(it, "build.gradle").exists() || File(it, "build.gradle.kts").exists()) 
}?.forEach { include("lib-multisrc:${it.name}") }
