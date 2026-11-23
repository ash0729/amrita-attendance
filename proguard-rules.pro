# GeckoView's consumer rules keep what its JNI layer needs; keep our own classes intact too.
-keep class com.ash.attendance.** { *; }
-dontwarn org.mozilla.**
