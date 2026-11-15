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
    }
}

rootProject.name = "writer"

include(
    ":app",
    ":core:content",
    ":core:editor",
    ":core:render",
    ":core:sync",
    ":core:ai",
    ":core:search",
    ":core:crypto",
    ":core:egress",
)
