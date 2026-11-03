# R8 keep rules for the release build.
#
# Goal: keep minification aggressive (the 47 MB was almost entirely dead code), while preserving the
# few entry points that are reached by name at runtime instead of by reference.

# --- kotlinx.serialization ---------------------------------------------------
# The runtime resolves a class' serializer by reflection: `decodeFromString<T>()` ends up looking up
# the companion's `serializer()` method, and the generated `$$serializer` object plus the companion
# itself are only reachable that way. Official kotlinx.serialization/R8 rule set, scoped to our DTOs
# (graph.GraphDtos.* and update.UpdateChecker's ReleaseDto/AssetDto) so nothing else is kept whole.
-keep,includedescriptorclasses class com.anderson.onedrivesync.**$$serializer { *; }
-keepclassmembers class com.anderson.onedrivesync.** {
    *** Companion;
}
-keepclasseswithmembers class com.anderson.onedrivesync.** {
    kotlinx.serialization.KSerializer serializer(...);
}

# --- our own names ------------------------------------------------------------
# Renaming the app's own classes saves very little (the 47 MB was library dead code), but it costs
# every diagnosis channel this app has: the crash log would read `at m92.d(SourceFile:412)` and the
# 42 MB mapping.txt never ships (no ADB on the user's phone). `-keepnames` still allows shrinking and
# optimization — only the names are pinned — so `scripts/verify-release-apk.ps1` can look the classes
# up in the APK and crash frames stay readable.
-keepnames class com.anderson.onedrivesync.** { *; }

# --- debug info in crash logs -------------------------------------------------
# The app is shipped without ADB: the only stacktrace channel is filesDir/crash/ plus the in-app log,
# so obfuscated frames must keep a readable line number and a consistent file name. R8's default
# already keeps SourceFile/LineNumberTable, this pins it (and hides the original .kt names).
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile
