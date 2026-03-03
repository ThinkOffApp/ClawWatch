# Vosk JNI bridge and recognition classes are loaded across Java/native boundaries.
-keep class org.vosk.** { *; }
-keep class org.kaldi.** { *; }
-dontwarn org.vosk.**
-dontwarn org.kaldi.**

# Keep Wear Data Layer entrypoint service class.
-keep class com.thinkoff.clawwatch.ConfigSyncService { *; }

# Keep org.json classes used in runtime parsing paths.
-keep class org.json.** { *; }
-dontwarn org.json.**
