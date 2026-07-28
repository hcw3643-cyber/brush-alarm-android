// SPDX-FileCopyrightText: 2026 Leo Huang
// SPDX-License-Identifier: GPL-3.0-only

pluginManagement {
    val useChinaMirrors =
        providers.gradleProperty("brushAlarm.useChinaMirrors").orNull.toBoolean()
    repositories {
        if (useChinaMirrors) {
            maven("https://repo.huaweicloud.com/repository/maven")
            maven("https://maven.aliyun.com/repository/google")
            maven("https://mirrors.cloud.tencent.com/nexus/repository/maven-public")
        }
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    val useChinaMirrors =
        providers.gradleProperty("brushAlarm.useChinaMirrors").orNull.toBoolean()
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        if (useChinaMirrors) {
            maven("https://repo.huaweicloud.com/repository/maven")
            maven("https://maven.aliyun.com/repository/google")
            maven("https://mirrors.cloud.tencent.com/nexus/repository/maven-public")
        }
        google()
        mavenCentral()
    }
}

rootProject.name = "BrushAlarm"
include(":app")
