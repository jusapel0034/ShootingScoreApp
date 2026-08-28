package com.shootingscore

import android.graphics.Bitmap
import android.graphics.PointF
import android.util.Log
import org.opencv.android.Utils
import org.opencv.core.*
import org.opencv.imgproc.Imgproc
import org.opencv.utils.Converters
import kotlin.math.sqrt

data class Hit(val x: Float, val y: Float, val score: Int)

data class ProcessingResult(
    val warpedBitmap: Bitmap,
    val hits: List<Hit>,
    val totalScore: Int,
    val targetFound: Boolean = true
)

class ImageProcessor(private val detector: YoloDetector) {

    private val targetSize = 640.0
    private val center = Point(320.0, 320.0)
    
    // 10 ring to 1 ring radii for a 640x640 target
    // Assumes 10 rings, each 32 pixels wide (320px total radius)
    private val ringRadii = listOf(
        10 to 32.0,
        9 to 64.0,
        8 to 96.0,
        7 to 128.0,
        6 to 160.0,
        5 to 192.0,
        4 to 224.0,
        3 to 256.0,
        2 to 288.0,
        1 to 320.0
    )

    fun process(bitmap: Bitmap): ProcessingResult {
        val warped = warpTarget(bitmap) ?: return ProcessingResult(bitmap, emptyList(), 0, false)
        
        val warpedBitmap = Bitmap.createBitmap(targetSize.toInt(), targetSize.toInt(), Bitmap.Config.ARGB_8888)
        Utils.matToBitmap(warped, warpedBitmap)
        
        val detections = detector.detect(warpedBitmap)
        
        val hits = detections.map { det ->
            val hitX = det.boundingBox.centerX()
            val hitY = det.boundingBox.centerY()
            val dist = sqrt((hitX - 320.0).let { it * it } + (hitY - 320.0).let { it * it })
            
            val score = ringRadii.firstOrNull { dist <= it.second }?.first ?: 0
            Hit(hitX, hitY, score)
        }

        warped.release()

        return ProcessingResult(warpedBitmap, hits, hits.sumOf { it.score }, true)
    }

    fun processWithReference(bitmap: Bitmap, reference: Bitmap): ProcessingResult {
        val currentMat = Mat()
        Utils.bitmapToMat(bitmap, currentMat)

        val warpedCurrent = warpTarget(bitmap) ?: run {
            currentMat.release()
            return ProcessingResult(bitmap, emptyList(), 0, false)
        }
        
        // PERFORMANCE OPTIMIZATION
        val warpedRef = if (reference.width == targetSize.toInt() && reference.height == targetSize.toInt()) {
            val mat = Mat()
            Utils.bitmapToMat(reference, mat)
            mat
        } else {
            warpTarget(reference) ?: run {
                currentMat.release(); warpedCurrent.release()
                return ProcessingResult(bitmap, emptyList(), 0, false)
            }
        }

        // Convert to grayscale
        val grayCurrent = Mat()
        val grayRef = Mat()
        Imgproc.cvtColor(warpedCurrent, grayCurrent, Imgproc.COLOR_RGBA2GRAY)
        Imgproc.cvtColor(warpedRef, grayRef, Imgproc.COLOR_RGBA2GRAY)

        // 1. Sub-pixel Alignment
        val alignedCurrent = refineAlignment(grayRef, grayCurrent)

        // 2. Heavy Noise Reduction
        val smoothCurrent = Mat()
        val smoothRef = Mat()
        Imgproc.medianBlur(alignedCurrent, smoothCurrent, 5)
        Imgproc.medianBlur(grayRef, smoothRef, 5)
        Imgproc.GaussianBlur(smoothCurrent, smoothCurrent, Size(5.0, 5.0), 0.0)
        Imgproc.GaussianBlur(smoothRef, smoothRef, Size(5.0, 5.0), 0.0)

        // 3. Find difference - DARK ONLY
        val diff = Mat()
        Core.subtract(smoothRef, smoothCurrent, diff)
        
        // --- NEW: STATIC PATTERN MASKING ---
        // Find existing dark ink (rings/numbers) in reference
        val inkMask = Mat()
        Imgproc.threshold(smoothRef, inkMask, 120.0, 255.0, Imgproc.THRESH_BINARY_INV)
        // Dilate ink mask slightly to cover edges
        val inkKernel = Imgproc.getStructuringElement(Imgproc.MORPH_ELLIPSE, Size(5.0, 5.0))
        Imgproc.dilate(inkMask, inkMask, inkKernel)
        // Zero out diff where ink already exists
        val cleanDiff = Mat()
        Core.bitwise_and(diff, diff, cleanDiff, inkMask.let { 
            val m = Mat()
            Core.bitwise_not(it, m)
            m
        })
        inkMask.release(); inkKernel.release(); diff.release()

        // --- STABILITY & HAND FILTER ---
        val totalDiff = Core.sumElems(cleanDiff).`val`[0]
        if (totalDiff > 12000000.0) {
            cleanupMats(currentMat, warpedCurrent, warpedRef, grayCurrent, grayRef, alignedCurrent, smoothCurrent, smoothRef, cleanDiff)
            return ProcessingResult(warpedCurrentBitmap(warpedCurrent), emptyList(), 0, true)
        }

        // --- CIRCULAR MASKING ---
        val maskedDiff = Mat()
        val circleMask = Mat.zeros(cleanDiff.size(), CvType.CV_8U)
        Imgproc.circle(circleMask, Point(320.0, 320.0), 310, Scalar(255.0), -1)
        cleanDiff.copyTo(maskedDiff, circleMask)
        circleMask.release(); cleanDiff.release()

        // 4. Thresholding
        val meanVal = Core.mean(maskedDiff).`val`[0]
        val thresholdVal = (meanVal * 4.0).coerceIn(50.0, 100.0) // Stricter threshold
        Imgproc.threshold(maskedDiff, maskedDiff, thresholdVal, 255.0, Imgproc.THRESH_BINARY)
        
        val kernel = Imgproc.getStructuringElement(Imgproc.MORPH_ELLIPSE, Size(3.0, 3.0))
        Imgproc.morphologyEx(maskedDiff, maskedDiff, Imgproc.MORPH_OPEN, kernel)
        
        // 5. Contour detection
        val contours = mutableListOf<MatOfPoint>()
        Imgproc.findContours(maskedDiff, contours, Mat(), Imgproc.RETR_EXTERNAL, Imgproc.CHAIN_APPROX_SIMPLE)
        
        val hits = mutableListOf<Hit>()
        for (contour in contours) {
            val area = Imgproc.contourArea(contour)
            if (area > 5000 || area < 40) continue 

            // --- NEW: SOLIDITY FILTER ---
            val hull = MatOfInt()
            Imgproc.convexHull(contour, hull)
            val hullArea = Imgproc.contourArea(MatOfPoint(*contour.toArray().let { arr -> hull.toArray().map { arr[it] } }.toTypedArray()))
            val solidity = if (hullArea > 0) area / hullArea else 0.0
            hull.release()

            if (solidity < 0.7) continue // Ignore crescents/noise

            val moments = Imgproc.moments(contour)
            if (moments._m00 != 0.0) {
                val hitX = (moments._m10 / moments._m00).toFloat()
                val hitY = (moments._m01 / moments._m00).toFloat()
                
                // Adaptive Darkness Check
                val ry = (hitY.toInt()).coerceIn(10, grayCurrent.rows()-11)
                val rx = (hitX.toInt()).coerceIn(10, grayCurrent.cols()-11)
                val sampleROI = grayCurrent.submat(ry-10, ry+10, rx-10, rx+10)
                val minMax = Core.minMaxLoc(sampleROI)
                sampleROI.release()
                
                if (minMax.minVal < 90.0) { // Stricter seed detection
                    val dist = sqrt((hitX - 320.0).let { it * it } + (hitY - 320.0).let { it * it })
                    if (dist <= 315.0) {
                        val score = ringRadii.firstOrNull { dist <= it.second }?.first ?: 0
                        hits.add(Hit(hitX, hitY, score))
                    }
                }
            }
        }

        // CRASH FIX: Create bitmap BEFORE releasing mats
        val resBitmap = Bitmap.createBitmap(targetSize.toInt(), targetSize.toInt(), Bitmap.Config.ARGB_8888)
        Utils.matToBitmap(warpedCurrent, resBitmap)
        
        cleanupMats(currentMat, warpedCurrent, warpedRef, grayCurrent, grayRef, alignedCurrent, smoothCurrent, smoothRef, maskedDiff, kernel)

        return ProcessingResult(resBitmap, hits, hits.sumOf { it.score }, true)
    }

    private fun warpedCurrentBitmap(warped: Mat): Bitmap {
        val b = Bitmap.createBitmap(targetSize.toInt(), targetSize.toInt(), Bitmap.Config.ARGB_8888)
        Utils.matToBitmap(warped, b)
        return b
    }

    private fun cleanupMats(vararg mats: Mat?) {
        for (m in mats) m?.release()
    }

    /**
     * Aligns the current frame to the reference frame using ECC (Enhanced Correlation Coefficient)
     * This compensates for sub-pixel jitter that causes noise on ring edges.
     */
    private fun refineAlignment(ref: Mat, current: Mat): Mat {
        val warpMatrix = Mat.eye(3, 3, CvType.CV_32F)
        val criteria = TermCriteria(TermCriteria.EPS or TermCriteria.COUNT, 50, 0.001)
        
        return try {
            org.opencv.video.Video.findTransformECC(ref, current, warpMatrix, org.opencv.video.Video.MOTION_HOMOGRAPHY, criteria, Mat())
            val aligned = Mat()
            Imgproc.warpPerspective(current, aligned, warpMatrix, ref.size())
            aligned
        } catch (e: Exception) {
            current.clone()
        }
    }

    fun processWithAutoAlign(bitmap: Bitmap, reference: Bitmap?): ProcessingResult {
        val warpedCurrent = warpTarget(bitmap) ?: return ProcessingResult(bitmap, emptyList(), 0, false)
        
        val resBitmap = Bitmap.createBitmap(targetSize.toInt(), targetSize.toInt(), Bitmap.Config.ARGB_8888)
        Utils.matToBitmap(warpedCurrent, resBitmap)

        if (reference == null) {
            warpedCurrent.release()
            return ProcessingResult(resBitmap, emptyList(), 0, true)
        }

        val grayCurrent = Mat()
        val grayRef = Mat()
        val refMat = Mat()
        Utils.bitmapToMat(reference, refMat)
        
        Imgproc.cvtColor(warpedCurrent, grayCurrent, Imgproc.COLOR_RGBA2GRAY)
        if (refMat.channels() > 1) {
            Imgproc.cvtColor(refMat, grayRef, Imgproc.COLOR_RGBA2GRAY)
        } else {
            grayRef.push_back(refMat)
        }

        // Align
        val alignedCurrent = refineAlignment(grayRef, grayCurrent)

        // Noise reduction
        val smoothCurrent = Mat()
        val smoothRef = Mat()
        Imgproc.medianBlur(alignedCurrent, smoothCurrent, 5)
        Imgproc.medianBlur(grayRef, smoothRef, 5)
        Imgproc.GaussianBlur(smoothCurrent, smoothCurrent, Size(5.0, 5.0), 0.0)
        Imgproc.GaussianBlur(smoothRef, smoothRef, Size(5.0, 5.0), 0.0)

        // 3. Find difference - DARK ONLY
        val diff = Mat()
        Core.subtract(smoothRef, smoothCurrent, diff)
        
        // --- STATIC PATTERN MASKING ---
        val inkMask = Mat()
        Imgproc.threshold(smoothRef, inkMask, 120.0, 255.0, Imgproc.THRESH_BINARY_INV)
        val inkKernel = Imgproc.getStructuringElement(Imgproc.MORPH_ELLIPSE, Size(5.0, 5.0))
        Imgproc.dilate(inkMask, inkMask, inkKernel)
        val cleanDiff = Mat()
        Core.bitwise_and(diff, diff, cleanDiff, inkMask.let { 
            val m = Mat()
            Core.bitwise_not(it, m)
            m
        })
        inkMask.release(); inkKernel.release(); diff.release()

        // --- STABILITY & HAND FILTER ---
        val totalDiff = Core.sumElems(cleanDiff).`val`[0]
        if (totalDiff > 10000000.0) {
            cleanupMats(warpedCurrent, refMat, grayCurrent, grayRef, alignedCurrent, smoothCurrent, smoothRef, cleanDiff)
            return ProcessingResult(resBitmap, emptyList(), 0, true)
        }

        // --- CIRCULAR MASKING ---
        val circleMask = Mat.zeros(cleanDiff.size(), CvType.CV_8U)
        Imgproc.circle(circleMask, Point(320.0, 320.0), 310, Scalar(255.0), -1)
        val maskedDiff = Mat()
        cleanDiff.copyTo(maskedDiff, circleMask)
        circleMask.release(); cleanDiff.release()

        val meanVal = Core.mean(maskedDiff).`val`[0]
        val thresholdVal = (meanVal * 4.0).coerceIn(50.0, 100.0)
        Imgproc.threshold(maskedDiff, maskedDiff, thresholdVal, 255.0, Imgproc.THRESH_BINARY)
        
        val kernel = Imgproc.getStructuringElement(Imgproc.MORPH_ELLIPSE, Size(3.0, 3.0))
        Imgproc.morphologyEx(maskedDiff, maskedDiff, Imgproc.MORPH_OPEN, kernel)
        
        val contours = mutableListOf<MatOfPoint>()
        Imgproc.findContours(maskedDiff, contours, Mat(), Imgproc.RETR_EXTERNAL, Imgproc.CHAIN_APPROX_SIMPLE)
        
        val hits = mutableListOf<Hit>()
        for (contour in contours) {
            val area = Imgproc.contourArea(contour)
            if (area > 5000 || area < 40) continue 

            val hull = MatOfInt()
            Imgproc.convexHull(contour, hull)
            val hullArea = Imgproc.contourArea(MatOfPoint(*contour.toArray().let { arr -> hull.toArray().map { arr[it] } }.toTypedArray()))
            val solidity = if (hullArea > 0) area / hullArea else 0.0
            hull.release()

            if (solidity < 0.7) continue

            val moments = Imgproc.moments(contour)
            if (moments._m00 != 0.0) {
                val hitX = (moments._m10 / moments._m00).toFloat()
                val hitY = (moments._m01 / moments._m00).toFloat()
                
                // Adaptive Darkness Check
                val ry = (hitY.toInt()).coerceIn(10, grayCurrent.rows()-11)
                val rx = (hitX.toInt()).coerceIn(10, grayCurrent.cols()-11)
                val sampleROI = grayCurrent.submat(ry-10, ry+10, rx-10, rx+10)
                val minMax = Core.minMaxLoc(sampleROI)
                sampleROI.release()

                if (minMax.minVal < 90.0) {
                    val dist = sqrt((hitX - 320.0).let { it * it } + (hitY - 320.0).let { it * it })
                    if (dist <= 315.0) {
                        val score = ringRadii.firstOrNull { dist <= it.second }?.first ?: 0
                        hits.add(Hit(hitX, hitY, score))
                    }
                }
            }
        }

        // Cleanup
        cleanupMats(warpedCurrent, refMat, grayCurrent, grayRef, alignedCurrent, smoothCurrent, smoothRef, maskedDiff, kernel)

        return ProcessingResult(resBitmap, hits, hits.sumOf { it.score }, true)
    }

    fun getWarpedTarget(bitmap: Bitmap): Bitmap? {
        val warpedMat = warpTarget(bitmap) ?: return null
        val result = Bitmap.createBitmap(targetSize.toInt(), targetSize.toInt(), Bitmap.Config.ARGB_8888)
        Utils.matToBitmap(warpedMat, result)
        warpedMat.release()
        return result
    }

    private fun warpTarget(bitmap: Bitmap): Mat? {
        val src = Mat()
        Utils.bitmapToMat(bitmap, src)
        
        val gray = Mat()
        Imgproc.cvtColor(src, gray, Imgproc.COLOR_RGBA2GRAY)
        
        // PERFORMANCE OPTIMIZATION: Use a downscaled image for detection
        val searchScale = 0.5 // Increased to 0.5 for better ring detail
        val graySmall = Mat()
        Imgproc.resize(gray, graySmall, Size(), searchScale, searchScale, Imgproc.INTER_AREA)

        // 1. Find the Bullseye first on small image
        val bullseyeData = findBullseyeData(graySmall)
        val bullseyeCenter = if (bullseyeData != null) Point(bullseyeData.center.x / searchScale, bullseyeData.center.y / searchScale) else null
        
        // 2. Find Candidate Quads on small image
        val candidates = findCandidateQuads(graySmall)
        
        var bestWarped: Mat? = null
        var bestScore = -1.0
        
        for (quadSmall in candidates) {
            // Upscale corners back to original resolution
            val quad = quadSmall.map { Point(it.x / searchScale, it.y / searchScale) }.toTypedArray()
            
            // Apply a refined warp that considers the bullseye tilt if available
            val warped = if (bullseyeData != null) warpToTargetRefined(src, quad, bullseyeData) else warpToTarget(src, quad)
            val score = evaluateBullseye(warped)
            
            // Boost score if the quad contains our detected bullseye center
            var finalScore = score
            if (bullseyeCenter != null) {
                if (Imgproc.pointPolygonTest(MatOfPoint2f(*quad), bullseyeCenter, false) >= 0) {
                    finalScore += 50.0 
                }
            }
            
            if (finalScore > bestScore && score > 20.0) {
                bestScore = finalScore
                bestWarped?.release()
                bestWarped = warped
            } else {
                warped.release()
            }
        }

        graySmall.release(); src.release(); gray.release()
        
        if (bestWarped == null && bullseyeCenter != null) {
            // Fallback: centered de-warped crop
            return dewarpAroundBullseye(bitmap, bullseyeCenter, bullseyeData!!)
        }
        
        return bestWarped
    }

    private data class BullseyeData(val center: Point, val majorAxis: Double, val minorAxis: Double, val angle: Double)

    private fun findBullseyeData(gray: Mat): BullseyeData? {
        val blurred = Mat()
        Imgproc.GaussianBlur(gray, blurred, Size(7.0, 7.0), 1.5)
        
        val circles = Mat()
        // Reduced max radius for tighter locking on the center
        Imgproc.HoughCircles(blurred, circles, Imgproc.HOUGH_GRADIENT, 1.0, 
            gray.rows() / 4.0, 100.0, 30.0, (gray.rows() * 0.05).toInt(), (gray.rows() * 0.35).toInt())
        
        if (circles.cols() > 0) {
            val data = circles.get(0, 0)
            val center = Point(data[0], data[1])
            
            // Refine by fitting an ellipse to the dark mass at this center
            val roiSize = (data[2] * 1.3).toInt()
            val x = (center.x - roiSize).toInt().coerceIn(0, gray.cols() - 1)
            val y = (center.y - roiSize).toInt().coerceIn(0, gray.rows() - 1)
            val w = (roiSize * 2).coerceAtMost(gray.cols() - x)
            val h = (roiSize * 2).coerceAtMost(gray.rows() - y)
            
            if (w > 10 && h > 10) {
                val roi = gray.submat(Rect(x, y, w, h))
                val thresh = Mat()
                // Use Otsu to find the central bullseye mass precisely
                Imgproc.threshold(roi, thresh, 0.0, 255.0, Imgproc.THRESH_BINARY_INV or Imgproc.THRESH_OTSU)
                
                val contours = mutableListOf<MatOfPoint>()
                Imgproc.findContours(thresh, contours, Mat(), Imgproc.RETR_EXTERNAL, Imgproc.CHAIN_APPROX_SIMPLE)
                // Filter contours to find the one closest to our Hough center
                val bestContour = contours.filter { it.total() >= 5 }.minByOrNull { 
                    val m = Imgproc.moments(it)
                    if (m._m00 == 0.0) Double.MAX_VALUE else {
                        val cx = m._m10 / m._m00
                        val cy = m._m01 / m._m00
                        // ROI relative center is (roiSize, roiSize)
                        sqrt((cx - roiSize)*(cx - roiSize) + (cy - roiSize)*(cy - roiSize))
                    }
                }
                
                if (bestContour != null) {
                    val ellipse = Imgproc.fitEllipse(MatOfPoint2f(*bestContour.toArray()))
                    roi.release(); thresh.release(); blurred.release(); circles.release()
                    return BullseyeData(Point(ellipse.center.x + x, ellipse.center.y + y), 
                        ellipse.size.width.coerceAtLeast(ellipse.size.height),
                        ellipse.size.width.coerceAtMost(ellipse.size.height),
                        ellipse.angle)
                }
                roi.release(); thresh.release()
            }
            circles.release(); blurred.release()
            return BullseyeData(center, data[2] * 2, data[2] * 2, 0.0)
        }
        circles.release(); blurred.release()
        return null
    }

    private fun findCandidateQuads(gray: Mat): List<Array<Point>> {
        val quads = mutableListOf<Array<Point>>()
        
        // Use Morphological Gradient for better edge detection
        val kernel = Imgproc.getStructuringElement(Imgproc.MORPH_RECT, Size(3.0, 3.0))
        val gradient = Mat()
        Imgproc.morphologyEx(gray, gradient, Imgproc.MORPH_GRADIENT, kernel)
        
        val thresh = Mat()
        Imgproc.threshold(gradient, thresh, 0.0, 255.0, Imgproc.THRESH_BINARY or Imgproc.THRESH_OTSU)
        Imgproc.dilate(thresh, thresh, kernel)

        val contours = mutableListOf<MatOfPoint>()
        Imgproc.findContours(thresh, contours, Mat(), Imgproc.RETR_EXTERNAL, Imgproc.CHAIN_APPROX_SIMPLE)
        
        for (contour in contours) {
            val area = Imgproc.contourArea(contour)
            if (area > gray.width() * gray.height() * 0.05) {
                val hull = MatOfInt()
                Imgproc.convexHull(contour, hull)
                val hullPoints = contour.toArray().let { arr -> hull.toArray().map { arr[it] } }
                
                if (hullPoints.size >= 4) {
                    val tl = hullPoints.minByOrNull { it.x + it.y }!!
                    val br = hullPoints.maxByOrNull { it.x + it.y }!!
                    val tr = hullPoints.minByOrNull { it.y - it.x }!!
                    val bl = hullPoints.maxByOrNull { it.y - it.x }!!
                    quads.add(arrayOf(tl, tr, br, bl))
                }
            }
        }
        kernel.release(); gradient.release(); thresh.release()
        return quads
    }

    private fun warpToTargetRefined(src: Mat, corners: Array<Point>, bullseye: BullseyeData): Mat {
        // This is a placeholder for a more complex perspective fix that aligns with bullseye
        // For now, we perform standard warp as it's the best quad fit
        return warpToTarget(src, corners)
    }

    private fun dewarpAroundBullseye(bitmap: Bitmap, center: Point, data: BullseyeData): Mat {
        val src = Mat()
        Utils.bitmapToMat(bitmap, src)
        
        // Calculate stretch factor
        val stretch = data.majorAxis / data.minorAxis.coerceAtLeast(1.0)
        
        // Adjust crop size based on the bullseye size to keep target consistent
        // Bullseye (rings 7-10) is 128px radius in 640x640, so full target is 5x bullseye radius
        val bullseyeRadius = data.majorAxis / 2.0
        val targetRadiusInSrc = bullseyeRadius * (320.0 / 128.0) 
        
        // Define source points on the tilted plane
        val srcPts = Converters.vector_Point2f_to_Mat(listOf(
            Point(center.x - targetRadiusInSrc, center.y - targetRadiusInSrc * stretch),
            Point(center.x + targetRadiusInSrc, center.y - targetRadiusInSrc * stretch),
            Point(center.x + targetRadiusInSrc, center.y + targetRadiusInSrc * stretch),
            Point(center.x - targetRadiusInSrc, center.y + targetRadiusInSrc * stretch)
        ))
        
        val dstPts = Converters.vector_Point2f_to_Mat(listOf(
            Point(0.0, 0.0), Point(targetSize, 0.0),
            Point(targetSize, targetSize), Point(0.0, targetSize)
        ))
        
        val warped = Mat(Size(targetSize, targetSize), CvType.CV_8UC4)
        val h = Imgproc.getPerspectiveTransform(srcPts, dstPts)
        Imgproc.warpPerspective(src, warped, h, Size(targetSize, targetSize))
        
        h.release(); srcPts.release(); dstPts.release(); src.release()
        return warped
    }

    private fun warpToTarget(src: Mat, corners: Array<Point>): Mat {
        val warped = Mat(Size(targetSize, targetSize), CvType.CV_8UC4)
        val srcPts = Converters.vector_Point2f_to_Mat(listOf(*corners))
        val dstPts = Converters.vector_Point2f_to_Mat(listOf(
            Point(0.0, 0.0), Point(targetSize, 0.0),
            Point(targetSize, targetSize), Point(0.0, targetSize)
        ))
        val h = Imgproc.getPerspectiveTransform(srcPts, dstPts)
        Imgproc.warpPerspective(src, warped, h, Size(targetSize, targetSize))
        h.release(); srcPts.release(); dstPts.release()
        return warped
    }

    private fun evaluateBullseye(warped: Mat): Double {
        val gray = Mat()
        Imgproc.cvtColor(warped, gray, Imgproc.COLOR_RGBA2GRAY)
        
        // Center area: rings 7-10
        val centerRect = Rect(240, 240, 160, 160)
        val centerROI = gray.submat(centerRect)
        
        // Calculate contrast (Standard Deviation)
        // This works for BOTH Dark centers and Light centers
        val matMean = MatOfDouble()
        val matStdDev = MatOfDouble()
        Core.meanStdDev(centerROI, matMean, matStdDev)
        val stdDev = matStdDev.toArray()[0]
        
        // Also look for circles
        val circles = Mat()
        Imgproc.HoughCircles(centerROI, circles, Imgproc.HOUGH_GRADIENT, 1.0, 20.0, 100.0, 20.0, 10, 80)
        val circleCount = circles.cols()
        
        circles.release(); centerROI.release(); gray.release()
        matMean.release(); matStdDev.release()
        
        // Score based on circle presence and internal contrast
        return (stdDev * 2.0) + (circleCount * 50.0)
    }

    private fun cropAroundBullseye(bitmap: Bitmap, center: Point): Mat {
        val src = Mat()
        Utils.bitmapToMat(bitmap, src)
        
        // We need to decide how much to crop. 
        // Let's assume the bullseye is 20% of the target height.
        // A safe default for a target sheet is taking 4x the bullseye diameter.
        val cropSize = src.height() * 0.5 
        
        val x = (center.x - cropSize / 2).coerceIn(0.0, src.width() - cropSize)
        val y = (center.y - cropSize / 2).coerceIn(0.0, src.height() - cropSize)
        
        val roi = Rect(x.toInt(), y.toInt(), cropSize.toInt(), cropSize.toInt())
        val cropped = src.submat(roi)
        val resized = Mat()
        Imgproc.resize(cropped, resized, Size(targetSize, targetSize))
        
        src.release(); cropped.release()
        return resized
    }

    // For testing without real camera
    fun simulateShot(center: PointF, targetRadius: Float, accuracy: Float = 0.6f): Hit {
        val angle = Math.random() * 2 * Math.PI
        val maxOffset = targetRadius * (1f - accuracy)
        val distance = Math.random() * maxOffset

        val x = center.x + (distance * Math.cos(angle)).toFloat()
        val y = center.y + (distance * Math.sin(angle)).toFloat()

        val dist = distance.toDouble()
        val score = ringRadii.firstOrNull { dist <= it.second }?.first ?: 0

        return Hit(x, y, score)
    }
}
