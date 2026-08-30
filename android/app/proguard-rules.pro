# ============================================================================
# TenderBase release shrink/obfuscation rules (Sprint 11)
#
# AndroidX, Material, Room, WorkManager and Firebase Messaging all ship their
# own consumer rules, so only app-specific keeps belong here.
# ============================================================================

# Readable stack traces in crash reports.
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile

# Entry points the manifest/OS instantiate by name.
-keep class com.tenderbase.app.TenderMessagingService { *; }
-keep class com.tenderbase.app.TenderBaseApp { *; }

# Room entities are referenced from generated implementation classes; keep
# their field names so migrations and column mapping stay stable.
-keep class com.tenderbase.app.*Entity { *; }

# kotlin-metadata warnings from kapt-generated Room code are safe to silence.
-dontwarn kotlinx.coroutines.debug.**
