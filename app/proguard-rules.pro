-keep class com.google.mlkit.** { *; }
-keep class com.google.android.gms.internal.mlkit_vision_text_common.** { *; }
-dontwarn com.google.android.gms.**
-keep class com.googlecode.tesseract.android.** { *; }
-keep class ai.onnxruntime.** { *; }

# barcode-scanning and entity-extraction pull in WorkManager/Room to manage their model
# downloads. R8 full mode strips the WorkDatabase constructor Room creates by reflection,
# which crashes every launch with "Failed to create an instance of ... WorkDatabase".
-keep class * extends androidx.room.RoomDatabase {
    <init>();
}
-keep class androidx.work.impl.WorkDatabase_Impl { *; }
-keep class androidx.work.impl.WorkDatabase { *; }
