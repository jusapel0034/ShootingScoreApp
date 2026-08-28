package com.shootingscore

import android.content.Context
import android.graphics.Bitmap
import android.graphics.RectF
import android.util.Log
import org.tensorflow.lite.Interpreter
import org.tensorflow.lite.support.common.FileUtil
import org.tensorflow.lite.support.common.ops.NormalizeOp
import org.tensorflow.lite.support.image.ImageProcessor
import org.tensorflow.lite.support.image.TensorImage
import org.tensorflow.lite.support.image.ops.ResizeOp
import java.nio.ByteBuffer
import java.nio.ByteOrder

class YoloDetector(private val context: Context, private val modelPath: String = "yolov8_bullet.tflite") {

    private var interpreter: Interpreter? = null
    private val inputSize = 640
    private val numClasses = 1 // Only bullet holes
    
    // Model output shape for YOLOv8 is usually [1, 5, 8400] for 640x640 input (x, y, w, h, score)
    // Adjust based on specific model export
    private val outputShape = intArrayOf(1, 5, 8400)

    init {
        try {
            Log.d("YoloDetector", "Loading model from: $modelPath")
            val model = FileUtil.loadMappedFile(context, modelPath)
            val options = Interpreter.Options().apply {
                setNumThreads(4)
            }
            interpreter = Interpreter(model, options)
            Log.d("YoloDetector", "Model loaded successfully")
        } catch (e: Exception) {
            Log.e("YoloDetector", "Error loading model: ${e.message}")
            e.printStackTrace()
        }
    }

    fun detect(bitmap: Bitmap): List<Detection> {
        if (interpreter == null) {
            Log.e("YoloDetector", "Interpreter is null, skipping detection")
            return emptyList()
        }
        Log.d("YoloDetector", "Running detection on bitmap ${bitmap.width}x${bitmap.height}")

        val tensorImage = TensorImage(org.tensorflow.lite.DataType.FLOAT32)
        tensorImage.load(bitmap)

        val imageProcessor = org.tensorflow.lite.support.image.ImageProcessor.Builder()
            .add(ResizeOp(inputSize, inputSize, ResizeOp.ResizeMethod.BILINEAR))
            .add(NormalizeOp(0f, 255f))
            .build()

        val processedImage = imageProcessor.process(tensorImage)
        val outputBuffer = ByteBuffer.allocateDirect(1 * 5 * 8400 * 4).apply {
            order(ByteOrder.nativeOrder())
        }

        interpreter?.run(processedImage.buffer, outputBuffer)
        outputBuffer.rewind()

        val outputArray = FloatArray(1 * 5 * 8400)
        outputBuffer.asFloatBuffer().get(outputArray)

        return postProcess(outputArray)
    }

    private fun postProcess(output: FloatArray): List<Detection> {
        val detections = mutableListOf<Detection>()
        
        // YOLOv8 output: [x, y, w, h, confidence]
        // Transposed logic: we have 8400 boxes
        for (i in 0 until 8400) {
            val confidence = output[4 * 8400 + i]
            if (confidence > 0.45f) {
                val x = output[0 * 8400 + i]
                val y = output[1 * 8400 + i]
                val w = output[2 * 8400 + i]
                val h = output[3 * 8400 + i]

                val left = (x - w / 2f)
                val top = (y - h / 2f)
                val right = (x + w / 2f)
                val bottom = (y + h / 2f)

                detections.add(Detection(RectF(left, top, right, bottom), confidence))
            }
        }

        return nms(detections)
    }

    private fun nms(detections: List<Detection>): List<Detection> {
        val sorted = detections.sortedByDescending { it.confidence }.toMutableList()
        val selected = mutableListOf<Detection>()

        while (sorted.isNotEmpty()) {
            val first = sorted.removeAt(0)
            selected.add(first)

            val iterator = sorted.iterator()
            while (iterator.hasNext()) {
                val next = iterator.next()
                if (iou(first.boundingBox, next.boundingBox) > 0.45f) {
                    iterator.remove()
                }
            }
        }
        return selected
    }

    private fun iou(a: RectF, b: RectF): Float {
        val intersection = RectF()
        if (!intersection.setIntersect(a, b)) return 0f
        val interArea = intersection.width() * intersection.height()
        val unionArea = (a.width() * a.height()) + (b.width() * b.height()) - interArea
        return interArea / unionArea
    }

    fun close() {
        interpreter?.close()
    }

    data class Detection(val boundingBox: RectF, val confidence: Float)
}
