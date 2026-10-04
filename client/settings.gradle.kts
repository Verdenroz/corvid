pluginManagement {
    includeBuild("build-logic")
    repositories {
        google {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
                includeGroupByRegex("androidx.*")
            }
        }
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

rootProject.name = "corvid-client"
enableFeaturePreview("TYPESAFE_PROJECT_ACCESSORS")

include(":androidApp")
include(":desktopApp")
include(":app-common")
include(":core:model")
include(":core:common")
include(":core:text")
include(":core:designsystem")
include(":core:ui")
include(":core:jmap")
include(":core:testing")
include(":core:auth")
include(":core:keyring")
include(":core:prefs")
include(":core:domain")
include(":core:data")
include(":feature:signin")
include(":feature:mailbox")
include(":feature:compose")
include(":feature:filters")
include(":feature:settings")
