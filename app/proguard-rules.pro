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

# Edition-specific backends are selected through a shared reflection boundary.
-keep class com.screentranslation.app.ml.BergamotTranslationEngine {
    public <init>(android.content.Context, java.lang.String, java.lang.String);
}
-keep class com.screentranslation.app.ml.HyMt2Q4TranslationEngine {
    public <init>(android.content.Context, java.lang.String, java.lang.String);
}
-keep class com.screentranslation.app.ml.OnlineLlmTranslationEngine {
    public <init>(android.content.Context, java.lang.String, java.lang.String);
}
-keep class com.screentranslation.app.online.OnlineEditionBridge {
    public static java.lang.String configurationSummary(android.content.Context);
}
