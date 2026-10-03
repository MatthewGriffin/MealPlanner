# Keep Kotlinx Serialization
-keepattributes *Annotation*,ElementValue*,Signature,InnerClasses,EnclosingMethod
-keepclassmembers class * {
    @kotlinx.serialization.Serializable <fields>;
    @kotlinx.serialization.Serializer <fields>;
}
-keepclassmembers class * {
    *** Companion;
}
-keepclasseswithmembers class * {
    kotlinx.serialization.KSerializer serializer();
}

# Keep Contract Models
-keep class com.example.chatgpt_mealplanner.contract.** { *; }
-keep class com.example.chatgpt_mealplanner.retail.contract.** { *; }

# Keep Room generated code and entities
-keep class * extends androidx.room.RoomDatabase
-keep class com.example.chatgpt_mealplanner.core.database.entity.** { *; }

# Keep OkHttp & Retrofit
-dontwarn okhttp3.**
-dontwarn retrofit2.**
