package com.shootingscore

/*
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Base64
import io.flutter.embedding.engine.FlutterEngine
import io.flutter.plugin.common.MethodChannel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream

class ShootScoreBridge(
    private val scope: CoroutineScope,
    private val processor: ImageProcessor
) {
    private val CHANNEL = "com.shootingscore/engine"

    fun setup(flutterEngine: FlutterEngine) {
        MethodChannel(flutterEngine.dartExecutor.binaryMessenger, CHANNEL).setMethodCallHandler { call, result ->
            when (call.method) {
                "processImage" -> {
                    val bytes = call.argument<ByteArray>("image")
                    if (bytes != null) {
                        scope.launch {
                            val response = process(bytes)
                            withContext(Dispatchers.Main) {
                                result.success(response)
                            }
                        }
                    } else {
                        result.error("INVALID_ARGUMENT", "Image bytes are null", null)
                    }
                }
                else -> result.notImplemented()
            }
        }
    }

    private suspend fun process(bytes: ByteArray): String = withContext(Dispatchers.Default) {
        val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
        val result = processor.process(bitmap)

        val json = JSONObject()
        json.put("totalScore", result.totalScore)
        
        val hitsArray = JSONArray()
        result.hits.forEach { hit ->
            val hitJson = JSONObject()
            hitJson.put("x", hit.x)
            hitJson.put("y", hit.y)
            hitJson.put("score", hit.score)
            hitsArray.put(hitJson)
        }
        json.put("hits", hitsArray)

        // Encode warped bitmap to Base64 for Flutter to display
        val stream = ByteArrayOutputStream()
        result.warpedBitmap.compress(Bitmap.CompressFormat.JPEG, 90, stream)
        val base64 = Base64.encodeToString(stream.toByteArray(), Base64.NO_WRAP)
        json.put("warpedImage", base64)

        json.toString()
    }
}
*/
