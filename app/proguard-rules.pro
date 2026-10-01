-keep class com.k2fsa.sherpa.onnx.** { *; }
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
