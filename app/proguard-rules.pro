# WritersBlock release rules.
#
# Referenced by app/build.gradle.kts. Minification is off, but the file still has to exist —
# a missing proguard-rules.pro breaks `assembleRelease` before R8 ever runs.
#
# Kept minimal on purpose: nothing here is needed while isMinifyEnabled is false. When it is turned
# on, the rules below are the ones this app actually depends on.

# ML Kit's text recogniser is reached only through its own registrar, never by name from app code.
-keep class com.google.mlkit.** { *; }
-keep class com.google.android.gms.internal.mlkit_vision_text** { *; }

# Compose's runtime reflects over @Composable lambdas.
-dontwarn androidx.compose.**

# The overlay's state is stored as plain strings and re-read after a process restart. Nothing here
# relies on reflection, so no app classes need keeping.
