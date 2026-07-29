# ML Kit publishes consumer keep rules for its public APIs and native bindings.
# Preserve generic signatures and annotations used by task/model metadata.
-keepattributes Signature
-keepattributes *Annotation*

# Keep JNI method names when a transitive native dependency does not ship a rule.
-keepclasseswithmembernames,includedescriptorclasses class * {
    native <methods>;
}

# ONNX Runtime resolves Java classes and methods from JNI. Keep the complete
# binding surface so R8 full mode cannot rename or remove those entry points.
-keep class ai.onnxruntime.** { *; }

# Firebase ComponentDiscovery instantiates registrars by their manifest class
# names. R8 full mode otherwise removes their no-argument constructors.
-keep class * implements com.google.firebase.components.ComponentRegistrar {
    public <init>();
}
