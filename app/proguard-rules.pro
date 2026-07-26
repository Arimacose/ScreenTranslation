# ML Kit publishes consumer keep rules for its public APIs and native bindings.
# Preserve generic signatures and annotations used by task/model metadata.
-keepattributes Signature
-keepattributes *Annotation*

# Keep JNI method names when a transitive native dependency does not ship a rule.
-keepclasseswithmembernames,includedescriptorclasses class * {
    native <methods>;
}
