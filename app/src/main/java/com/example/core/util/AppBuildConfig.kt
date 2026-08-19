package com.example.core.util

/**
 * Safe wrapper around AGP generated BuildConfig fields.
 * Prevents NoClassDefFoundError / ClassNotFoundException when R8 minifies or strips
 * BuildConfig in production release builds.
 */
object AppBuildConfig {
    val DEBUG: Boolean
        get() = try {
            com.alamiry.earthlinkreseller.BuildConfig.DEBUG
        } catch (t: Throwable) {
            false
        }

    val FIREBASE_API_KEY: String
        get() = try {
            com.alamiry.earthlinkreseller.BuildConfig.FIREBASE_API_KEY
        } catch (t: Throwable) {
            ""
        }

    val FIREBASE_APPLICATION_ID: String
        get() = try {
            com.alamiry.earthlinkreseller.BuildConfig.FIREBASE_APPLICATION_ID
        } catch (t: Throwable) {
            ""
        }

    val FIREBASE_PROJECT_ID: String
        get() = try {
            com.alamiry.earthlinkreseller.BuildConfig.FIREBASE_PROJECT_ID
        } catch (t: Throwable) {
            ""
        }

    val FIREBASE_DATABASE_URL: String
        get() = try {
            com.alamiry.earthlinkreseller.BuildConfig.FIREBASE_DATABASE_URL
        } catch (t: Throwable) {
            ""
        }
}
