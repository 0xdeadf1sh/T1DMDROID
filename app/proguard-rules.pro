# R8 keep rules for the optimized release build.
#
# The app has two native surfaces R8 cannot reason about, both mapped by NAME/REFLECTION at runtime,
# so shrinking or renaming their members corrupts the FFI ABI (compiles clean, crashes on first call):
#   1. the Rust core (`t1dm-core`) reached through uniffi's JNA bindings, and
#   2. ExecuTorch's JNI inference runtime.

# ── JNA — uniffi loads the Rust .so through JNA, which maps Java method names to native symbols and
#    struct fields to C fields by reflection. Nothing here may be stripped, renamed, or reordered. ──
-keep class com.sun.jna.** { *; }
-keep interface com.sun.jna.** { *; }
-keep class * implements com.sun.jna.Library { *; }
-keep class * implements com.sun.jna.Callback { *; }
-keep class * extends com.sun.jna.Structure { *; }
-dontwarn com.sun.jna.**
-dontwarn java.awt.**

# ── uniffi-generated FFI bindings: the interface method names ARE the exported Rust symbols and the
#    Structure field names ARE the C layout, so keep the whole generated package verbatim. ──
-keep class uniffi.** { *; }
-keep interface uniffi.** { *; }

# ── ExecuTorch (JNI via fbjni). The stock AAR ships consumer rules, but the vendored Vulkan AAR does
#    not. ExecuTorch's Java Module holds its native C++ object through an fbjni HybridData whose
#    `mNativePointer` field the C++ side reads BY NAME — obfuscate it and `Module.load` aborts with
#    'ptr' the instant a model loads. Keep the bindings AND fbjni/SoLoader (com.facebook.**) verbatim. ──
-keep class org.pytorch.** { *; }
-keep class com.facebook.jni.** { *; }
-keep class com.facebook.soloader.** { *; }
-keepclassmembers class * { @com.facebook.jni.annotations.DoNotStrip *; }
-dontwarn org.pytorch.**
-dontwarn com.facebook.**

# ── Any remaining JNI entry points. ──
-keepclasseswithmembernames,includedescriptorclasses class * {
    native <methods>;
}

# ── Enums resolved from persisted strings via valueOf(...) (VibrationPreset, TempUnit, UnitSpace,
#    BackendId, objective/rail keys, …). Keep the synthesized accessors R8 would otherwise prune. ──
-keepclassmembers enum * {
    public static **[] values();
    public static ** valueOf(java.lang.String);
}
