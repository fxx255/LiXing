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

# jlatexmath 内部用 Class.forName(类名).newInstance() 反射实例化 XML 预定义的
# 命令/公式实现类（\operatorname、\dfrac、cases 环境等一批都走这条路）。
# R8 改名后按原名查找失败 → 返回 null → NPE，真机表现为
# 「⚠ 公式无法渲染 (NullPointerException)」，且只有走反射注册的那部分公式挂
# （硬编码路径的 \int、\frac、E[...] 正常），debug 构建不混淆所以测试全绿。
# 整包保留原名，体积代价约 1~2MB。
-keep class org.scilab.forge.jlatexmath.** { *; }
-dontwarn org.scilab.forge.jlatexmath.**
