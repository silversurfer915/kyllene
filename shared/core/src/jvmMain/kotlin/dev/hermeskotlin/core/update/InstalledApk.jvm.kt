package dev.hermeskotlin.core.update

// A desktop has no APK to hash, and never takes the phone's update feed.
internal actual suspend fun installedApkSha256(): String? = null
