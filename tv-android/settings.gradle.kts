pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
        // NewPipeExtractor (sideload flavor, ADR-0100 §2) is published on JitPack only. The filter
        // keeps JitPack from serving anything else: the extractor's group, plus its nanojson fork,
        // which JitPack publishes under the case-sensitive group com.github.TeamNewPipe.
        maven("https://jitpack.io") {
            content {
                includeGroup("com.github.teamnewpipe")
                includeGroup("com.github.TeamNewPipe")
            }
        }
    }
}

rootProject.name = "VistaksTV"
include(":app")
