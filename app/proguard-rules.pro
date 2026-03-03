# Vosk JNI bridge and recognition classes are loaded across Java/native boundaries.
-keep class org.vosk.** { *; }
-keep class org.kaldi.** { *; }
-dontwarn org.vosk.**
-dontwarn org.kaldi.**
