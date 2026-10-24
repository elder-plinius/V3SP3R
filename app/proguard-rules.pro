# ============================================================================
# V3SP3R / Vesper — R8 rules for release builds
# ============================================================================
# Keep rules are intentionally narrow: package-wide keeps defeat R8 and hide
# dead code. Library consumer rules are listed where they replace local rules.

# ── General Android ─────────────────────────────────────────────────────────

# Runtime annotations drive kotlinx.serialization, Hilt and Room; signatures
# are needed by generic serializers and reflection.
-keepattributes *Annotation*
-keepattributes Signature
-keepattributes InnerClasses,EnclosingMethod
-keepattributes SourceFile,LineNumberTable

# The framework looks up Parcelable CREATOR fields reflectively.
-keepclassmembers class * implements android.os.Parcelable {
    public static final ** CREATOR;
}

# Enum values()/valueOf() are accessed reflectively by serializers and Room.
-keepclassmembers enum * {
    public static **[] values();
    public static ** valueOf(java.lang.String);
}

# ── Kotlin / Coroutines ─────────────────────────────────────────────────────
# kotlinx-coroutines-core 1.10.1 ships META-INF/com.android.tools/proguard/
# coroutines.pro, which keeps ServiceLoader names and volatile fields. A
# package-wide keep would retain every coroutine class, so keep only the
# reflective volatile-field access that the library relies on.
-keepclassmembers class kotlinx.coroutines.** {
    volatile <fields>;
}

# ── Kotlinx Serialization ───────────────────────────────────────────────────
# Serializers are resolved through generated $$serializer classes and the
# companion serializer() factory. Keep those, not every app class.
-keep,includedescriptorclasses class com.vesper.flipper.**$$serializer { *; }
-keepclassmembers class com.vesper.flipper.** {
    *** Companion;
}
-keepclasseswithmembers class com.vesper.flipper.** {
    kotlinx.serialization.KSerializer serializer(...);
}

# ── OkHttp ──────────────────────────────────────────────────────────────────
# OkHttp 4.12 ships META-INF/proguard/okhttp3.pro (PublicSuffixDatabase name
# and optional-platform dontwarn). No application keep rule is required.

# ── Protobuf ────────────────────────────────────────────────────────────────
# protobuf-java 3.25.5 ships no proguard rules. Generated messages are
# referenced directly, but GeneratedMessageV3/Lite read fields reflectively;
# keep fields only, never the whole com.google.protobuf package.
-keepclassmembers class * extends com.google.protobuf.GeneratedMessageV3 {
    <fields>;
}
-keepclassmembers class * extends com.google.protobuf.GeneratedMessageLite {
    <fields>;
}
-dontwarn com.google.protobuf.**

# ── Room Database ───────────────────────────────────────────────────────────
# room-runtime ships a consumer rule that keeps RoomDatabase constructors and
# suppresses paging warnings. Generated _Impl classes are direct references;
# keep entity classes because Room binds them through generated adapters.
-keep @androidx.room.Entity class *
-dontwarn androidx.room.paging.**

# ── Hilt / Dagger ───────────────────────────────────────────────────────────
# hilt-android's AAR ships consumer rules for the reflective EntryPoints
# casts. Keep those annotations explicitly; do not keep all of dagger.**.
-keep,allowobfuscation,allowshrinking @dagger.hilt.EntryPoint class *
-keep,allowobfuscation,allowshrinking @dagger.hilt.internal.ComponentEntryPoint class *
-keep,allowobfuscation,allowshrinking @dagger.hilt.internal.GeneratedEntryPoint class *
-keep,allowobfuscation,allowshrinking @dagger.hilt.android.EarlyEntryPoint class *
-dontwarn dagger.**

# ── Tink / Security Crypto ──────────────────────────────────────────────────
# security-crypto 1.1.0-alpha06 ships no consumer rules and Tink resolves key
# managers through its runtime registry. Keep Tink while EncryptedSharedPrefs
# is on the data path; narrow this only after a release-mode device test.
-dontwarn com.google.crypto.tink.**
-keep class com.google.crypto.tink.** { *; }

# ── USB Serial ──────────────────────────────────────────────────────────────
# usb-serial-for-android 3.11.0 ships proguard.txt with a driver-package keep;
# adding another keep here would only duplicate it.

# ── Compose ─────────────────────────────────────────────────────────────────
# Compose ships consumer rules with AGP 8+; no application keep is required.

# ── App models ──────────────────────────────────────────────────────────────
# No package-wide keeps for ai/data/voice: kotlinx.serialization, Room @Entity
# and Parcelable CREATOR rules above cover the reflective access paths.
