# Keep app classes
-keep class com.shootingscore.** { *; }

# TensorFlow Lite GPU delegate missing classes
-dontwarn org.tensorflow.lite.gpu.GpuDelegateFactory$Options$GpuBackend
-dontwarn org.tensorflow.lite.gpu.GpuDelegateFactory$Options
