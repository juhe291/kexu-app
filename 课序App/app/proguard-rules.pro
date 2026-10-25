# 课序 1.0 release R8 规则
# 说明：大部分依赖（Compose / Room / WorkManager / DataStore / ML Kit）自带 consumer rules；
# 这里只保留 PDFBox-Android、bouncycastle 与反射相关部分，避免混淆后运行时崩溃。

# PDFBox-Android：解析 PDF 时用到反射与资源加载
-keep class com.tom_roush.pdfbox.** { *; }
-dontwarn com.tom_roush.pdfbox.**
-dontwarn org.bouncycastle.**
-dontwarn org.apache.commons.logging.**
-dontwarn javax.annotation.**
-dontwarn java.awt.**
-dontwarn javax.imageio.**

# ML Kit / Firebase 组件（OCR 模型由库加载）
-keep class com.google.mlkit.** { *; }
-keep class com.google.android.gms.internal.mlkit_vision_text** { *; }
-dontwarn com.google.mlkit.**

# org.json（备份/导入/JSON 课表模板）
-keep class org.json.** { *; }

# Kotlin 元数据与协程
-keepattributes *Annotation*, InnerClasses, Signature, Exceptions
-dontwarn kotlinx.coroutines.**
