# ShootingScoreApp - Flutter Integration Guide

This project provides a high-performance image processing engine for detecting bullet holes on shooting targets using OpenCV and YOLOv8.

## Native Implementation
The Android side implements a `MethodChannel` named `com.shootingscore/engine`.

### Method: `processImage`
- **Arguments**: `Map<String, dynamic>` containing:
  - `image`: `Uint8List` (JPEG/PNG bytes of the target photo).
- **Returns**: `String` (JSON encoded result).

## Flutter Usage

### 1. Define the Channel
```dart
static const platform = MethodChannel('com.shootingscore/engine');
```

### 2. Invoke the Engine
```dart
Future<void> analyzeTarget(Uint8List imageBytes) async {
  try {
    final String resultJson = await platform.invokeMethod('processImage', {
      'image': imageBytes,
    });
    
    final Map<String, dynamic> result = jsonDecode(resultJson);
    
    int totalScore = result['totalScore'];
    List hits = result['hits']; // List of {x, y, score}
    String base64Warped = result['warpedImage']; // The 640x640 processed target
    
    // Use base64Warped to show the 'flat' target to the user
    // Image.memory(base64Decode(base64Warped))
  } on PlatformException catch (e) {
    print("Failed to process image: '${e.message}'.");
  }
}
```

## Setup Requirements
1. **Model File**: Ensure `yolov8_bullet.tflite` is placed in `app/src/main/assets/`.
2. **OpenCV**: The Android project expects the OpenCV SDK to be initialized. The current implementation uses `OpenCVLoader.initDebug()`.
3. **Permissions**: Ensure Camera permissions are handled in your Flutter app before calling the native side if you use the native camera capture (though the bridge accepts bytes).

## Scoring Logic
- The engine warps the target to a **640x640** square.
- The center point is **(320, 320)**.
- Hit coordinates are relative to this 640x640 space.
