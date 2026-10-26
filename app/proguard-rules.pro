# Desert Ant Labs JNI & Native Bindings
-keep class ai.desertant.** { *; }
-keepclassmembers class ai.desertant.** {
    native <methods>;
    *;
}
-keepnames class ai.desertant.**
-keep interface ai.desertant.** { *; }

# LiteRT / TensorFlow Lite Runtime & Delegates
-keep class org.tensorflow.lite.** { *; }
-keep class com.google.ai.edge.litert.** { *; }
-dontwarn org.tensorflow.lite.**
-dontwarn com.google.ai.edge.litert.**

# Google AI Edge LiteRT-LM (Experimental Gemma 4 E2B on NPU / GPU / CPU) & Gson JNI Payloads
-keep class com.google.ai.edge.litertlm.** { *; }
-keepclassmembers class com.google.ai.edge.litertlm.** {
    native <methods>;
    *;
}
-keep class com.google.gson.** { *; }
-dontwarn com.google.ai.edge.litertlm.**
-dontwarn kotlin.reflect.jvm.internal.**

# ONNX Runtime Android (NNAPI / QNN NPU & XNNPACK Execution Providers)
-keep class ai.onnxruntime.** { *; }
-keepclassmembers class ai.onnxruntime.** {
    native <methods>;
    *;
}
-dontwarn ai.onnxruntime.**

# OpenFlow Core & Persistence
-keep class com.voicebubble.openflow.core.database.** { *; }
-keep class com.voicebubble.openflow.core.speech.** { *; }
-keep class androidx.compose.** { *; }
-keep class androidx.room.** { *; }
-keep class androidx.datastore.** { *; }
-keepclassmembers class * {
    @androidx.room.* <methods>;
    @androidx.room.* <fields>;
}
-keep class * extends androidx.room.RoomDatabase { *; }

