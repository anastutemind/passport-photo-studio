# Keep ML Kit classes
-keep class com.google.mlkit.** { *; }
-dontwarn com.google.mlkit.**

# Keep ONNX Runtime (uses JNI; classes/fields are accessed from native code)
-keep class ai.onnxruntime.** { *; }
-dontwarn ai.onnxruntime.**
