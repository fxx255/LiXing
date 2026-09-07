# Room / Hilt / WorkManager 自带 consumer rules，这里只补充序列化相关。
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.**

# kotlinx.serialization 生成的 serializer
-keepclassmembers class com.example.lixing.** {
    *** Companion;
}
-keepclasseswithmembers class com.example.lixing.** {
    kotlinx.serialization.KSerializer serializer(...);
}
-keep,includedescriptorclasses class com.example.lixing.**$$serializer { *; }

# 枚举在 Room / 序列化中按名字使用
-keepclassmembers enum com.example.lixing.** {
    public static **[] values();
    public static ** valueOf(java.lang.String);
}
