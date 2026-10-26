package com.cretorix.buildlogic

import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.TaskAction
import org.gradle.work.DisableCachingByDefault

@DisableCachingByDefault(because = "This task only validates release configuration.")
abstract class VerifyAdMobReleaseConfiguration : DefaultTask() {
    @get:Input
    abstract val admobAppId: Property<String>

    @get:Input
    abstract val admobUnlockBannerId: Property<String>

    @TaskAction
    fun verify() {
        if (admobAppId.get().isBlank() || admobUnlockBannerId.get().isBlank()) {
            throw GradleException(
                "Set ADMOB_APP_ID and ADMOB_UNLOCK_BANNER_ID in your user-level Gradle properties before building a release."
            )
        }
    }
}
