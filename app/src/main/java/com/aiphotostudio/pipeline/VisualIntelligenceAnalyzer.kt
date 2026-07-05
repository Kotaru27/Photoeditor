package com.aiphotostudio.pipeline

import android.graphics.Bitmap
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/** Offline measurement-only analyzer. No generative AI, no object replacement. */
object VisualIntelligenceAnalyzer {
    fun analyze(bitmap: Bitmap): AnalysisResult {
        val width = bitmap.width
        val height = bitmap.height
        val step = max(1, max(width, height) / 900)
        val pixels = IntArray(width * height)
        bitmap.getPixels(pixels, 0, width, 0, 0, width, height)
        val hist = IntArray(256)
        var count = 0
        var luminanceSum = 0.0
        var rSum = 0.0
        var gSum = 0.0
        var bSum = 0.0
        var clippedShadow = 0
        var clippedHighlight = 0
        var saturatedPixels = 0
        var edgeEnergy = 0.0
        var previousLum = -1

        var topLum = 0.0; var topCount = 0
        var midLum = 0.0; var midCount = 0
        var bottomLum = 0.0; var bottomCount = 0
        var warmPixels = 0
        var coolPixels = 0
        var greenPixels = 0
        var skinLikePixels = 0
        var smoothPixels = 0
        var brightTopPixels = 0
        var centerLum = 0.0; var centerCount = 0
        var centerDetail = 0.0
        var centerWarmPixels = 0
        var centerSaturatedPixels = 0
        var borderLum = 0.0; var borderCount = 0
        var borderDetail = 0.0
        var backgroundColorNoise = 0.0

        var y = 0
        while (y < height) {
            var x = 0
            while (x < width) {
                val c = pixels[y * width + x]
                val r = (c shr 16) and 255
                val g = (c shr 8) and 255
                val b = c and 255
                val lum = (0.2126f * r + 0.7152f * g + 0.0722f * b).toInt().coerceIn(0, 255)
                hist[lum]++
                luminanceSum += lum
                rSum += r
                gSum += g
                bSum += b
                if (lum <= 4) clippedShadow++
                if (lum >= 251) clippedHighlight++
                if (max(r, max(g, b)) - min(r, min(g, b)) > 75) saturatedPixels++
                if (previousLum >= 0) edgeEnergy += abs(lum - previousLum)
                previousLum = lum

                when {
                    y < height / 3 -> { topLum += lum; topCount++ }
                    y < height * 2 / 3 -> { midLum += lum; midCount++ }
                    else -> { bottomLum += lum; bottomCount++ }
                }
                if (r > b + 24 && r > 95 && g > 55) warmPixels++
                if (b > r + 18 && b > 90) coolPixels++
                if (g > r + 15 && g > b + 15 && g > 80) greenPixels++
                if (r in 80..245 && g in 45..210 && b in 30..185 && r > g && g > b && r - b > 22) skinLikePixels++
                if (previousLum >= 0 && abs(lum - previousLum) < 4) smoothPixels++
                if (y < height / 3 && lum > 165 && b > r - 8) brightTopPixels++

                val nx = (x.toFloat() / width.toFloat()) - 0.5f
                val ny = (y.toFloat() / height.toFloat()) - 0.5f
                val centerDistance = kotlin.math.sqrt(nx * nx + ny * ny)
                if (centerDistance < 0.34f) {
                    centerLum += lum
                    centerCount++
                    if (previousLum >= 0) centerDetail += abs(lum - previousLum)
                    if (r > b + 18 && r > 75 && g > 45) centerWarmPixels++
                    if (max(r, max(g, b)) - min(r, min(g, b)) > 42) centerSaturatedPixels++
                }
                if (centerDistance > 0.44f) {
                    borderLum += lum
                    borderCount++
                    if (previousLum >= 0) borderDetail += abs(lum - previousLum)
                    backgroundColorNoise += max(r, max(g, b)) - min(r, min(g, b))
                }

                count++
                x += step
            }
            y += step
        }

        val avgLum = if (count == 0) 128f else (luminanceSum / count / 255.0).toFloat()
        val avgR = (rSum / max(1, count)).toFloat()
        val avgG = (gSum / max(1, count)).toFloat()
        val avgB = (bSum / max(1, count)).toFloat()
        val shadowClip = clippedShadow.toFloat() / max(1, count)
        val highlightClip = clippedHighlight.toFloat() / max(1, count)
        val saturationEstimate = saturatedPixels.toFloat() / max(1, count)
        val sharpness = (edgeEnergy / max(1, count) / 32.0).toFloat().coerceIn(0f, 1f)

        var p5 = 0
        var p50 = 128
        var p95 = 255
        var cumulative = 0
        val lowTarget = (count * 0.05f).toInt()
        val midTarget = (count * 0.50f).toInt()
        val highTarget = (count * 0.95f).toInt()
        for (i in 0..255) {
            cumulative += hist[i]
            if (cumulative <= lowTarget) p5 = i
            if (cumulative <= midTarget) p50 = i
            if (cumulative <= highTarget) p95 = i
        }
        val dynamicRange = ((p95 - p5) / 255f).coerceIn(0f, 1f)
        val midtonePosition = (p50 / 255f).coerceIn(0f, 1f)

        val warmthCast = ((avgR - avgB) / 255f).coerceIn(-1f, 1f)
        val tintCast = (((avgR + avgB) / 2f - avgG) / 255f).coerceIn(-1f, 1f)
        val noise = estimateNoise(pixels, width, height, step).coerceIn(0f, 1f)

        val topAverage = (topLum / max(1, topCount) / 255.0).toFloat()
        val middleAverage = (midLum / max(1, midCount) / 255.0).toFloat()
        val bottomAverage = (bottomLum / max(1, bottomCount) / 255.0).toFloat()
        val skyLikelihood = (brightTopPixels.toFloat() / max(1, topCount)).coerceIn(0f, 1f)
        val peopleLikelihood = (skinLikePixels.toFloat() / max(1, count) * 3.5f).coerceIn(0f, 1f)
        val natureLikelihood = (greenPixels.toFloat() / max(1, count) * 2.2f).coerceIn(0f, 1f)
        val warmSceneStrength = (warmPixels.toFloat() / max(1, count)).coerceIn(0f, 1f)
        val coolSceneStrength = (coolPixels.toFloat() / max(1, count)).coerceIn(0f, 1f)
        val smoothRegionStrength = (smoothPixels.toFloat() / max(1, count)).coerceIn(0f, 1f)
        val centerAverage = (centerLum / max(1, centerCount) / 255.0).toFloat()
        val borderAverage = (borderLum / max(1, borderCount) / 255.0).toFloat()
        val centerDetailStrength = (centerDetail / max(1, centerCount) / 24.0).toFloat().coerceIn(0f, 1f)
        val borderDetailStrength = (borderDetail / max(1, borderCount) / 28.0).toFloat().coerceIn(0f, 1f)
        val backgroundDistraction = ((backgroundColorNoise / max(1, borderCount)) / 95.0).toFloat().coerceIn(0f, 1f)
        val centerWarmStrength = (centerWarmPixels.toFloat() / max(1, centerCount) * 1.8f).coerceIn(0f, 1f)
        val centerColorStrength = (centerSaturatedPixels.toFloat() / max(1, centerCount) * 1.4f).coerceIn(0f, 1f)
        val centerSeparation = kotlin.math.abs(centerAverage - borderAverage).coerceIn(0f, 1f)
        val denseMap = DensePixelAnalyzer.analyze(bitmap)
        val regionMap = RegionMapAnalyzer.analyze(bitmap)
        val dense = denseMap.summary
        val subjectFocusLikelihood = (centerDetailStrength * 0.24f + centerSeparation * 0.12f + centerWarmStrength * 0.07f + centerColorStrength * 0.06f + regionMap.centerSaliency * 0.18f + dense.centerSaliency * 0.33f).coerceIn(0f, 1f)
        val foregroundHeroLikelihood = (subjectFocusLikelihood * 0.28f + centerWarmStrength * 0.12f + (centerDetailStrength - borderDetailStrength * 0.45f).coerceIn(0f, 1f) * 0.16f + regionMap.ornateObjectConfidence * 0.20f + dense.warmObjectPresence * 0.24f).coerceIn(0f, 1f)
        val ornateObjectLikelihood = (centerDetailStrength * 0.24f + centerWarmStrength * 0.14f + foregroundHeroLikelihood * 0.12f + regionMap.ornateObjectConfidence * 0.22f + dense.warmObjectPresence * 0.28f).coerceIn(0f, 1f)
        val portraitSafetyLikelihood = (peopleLikelihood * 0.26f + regionMap.portraitConfidence * 0.22f + dense.skinPresence * 0.28f + smoothRegionStrength * 0.12f + (1f - maxOf(centerDetailStrength, dense.averageTexture)).coerceIn(0f, 1f) * 0.12f - ornateObjectLikelihood * 0.45f).coerceIn(0f, 1f)

        return AnalysisResult(
            averageLuminance = avgLum,
            dynamicRange = dynamicRange,
            midtonePosition = midtonePosition,
            shadowClipping = shadowClip,
            highlightClipping = highlightClip,
            warmthCast = warmthCast,
            tintCast = tintCast,
            saturationEstimate = saturationEstimate,
            sharpnessEstimate = sharpness,
            noiseEstimate = noise,
            topLuminance = topAverage,
            middleLuminance = middleAverage,
            bottomLuminance = bottomAverage,
            skyLikelihood = skyLikelihood,
            peopleLikelihood = peopleLikelihood,
            natureLikelihood = natureLikelihood,
            warmSceneStrength = warmSceneStrength,
            coolSceneStrength = coolSceneStrength,
            smoothRegionStrength = smoothRegionStrength,
            centerLuminance = centerAverage,
            borderLuminance = borderAverage,
            centerDetailStrength = centerDetailStrength,
            subjectFocusLikelihood = subjectFocusLikelihood,
            backgroundDistraction = backgroundDistraction,
            foregroundHeroLikelihood = foregroundHeroLikelihood,
            centerWarmStrength = centerWarmStrength,
            portraitSafetyLikelihood = portraitSafetyLikelihood,
            ornateObjectLikelihood = ornateObjectLikelihood,
            regionMap = regionMap,
            denseMap = denseMap
        )
    }

    private fun estimateNoise(pixels: IntArray, width: Int, height: Int, baseStep: Int): Float {
        val step = max(2, baseStep * 2)
        var samples = 0
        var localVariance = 0.0
        var y = step
        while (y < height - step) {
            var x = step
            while (x < width - step) {
                val center = lum(pixels[y * width + x])
                val near = (lum(pixels[y * width + x - step]) + lum(pixels[y * width + x + step]) +
                    lum(pixels[(y - step) * width + x]) + lum(pixels[(y + step) * width + x])) / 4f
                localVariance += abs(center - near)
                samples++
                x += step * 8
            }
            y += step * 8
        }
        return (localVariance / max(1, samples) / 42.0).toFloat()
    }

    private fun lum(c: Int): Float {
        val r = (c shr 16) and 255
        val g = (c shr 8) and 255
        val b = c and 255
        return 0.2126f * r + 0.7152f * g + 0.0722f * b
    }
}

data class AnalysisResult(
    val averageLuminance: Float,
    val dynamicRange: Float,
    val midtonePosition: Float,
    val shadowClipping: Float,
    val highlightClipping: Float,
    val warmthCast: Float,
    val tintCast: Float,
    val saturationEstimate: Float,
    val sharpnessEstimate: Float,
    val noiseEstimate: Float,
    val topLuminance: Float,
    val middleLuminance: Float,
    val bottomLuminance: Float,
    val skyLikelihood: Float,
    val peopleLikelihood: Float,
    val natureLikelihood: Float,
    val warmSceneStrength: Float,
    val coolSceneStrength: Float,
    val smoothRegionStrength: Float,
    val centerLuminance: Float,
    val borderLuminance: Float,
    val centerDetailStrength: Float,
    val subjectFocusLikelihood: Float,
    val backgroundDistraction: Float,
    val foregroundHeroLikelihood: Float,
    val centerWarmStrength: Float,
    val portraitSafetyLikelihood: Float,
    val ornateObjectLikelihood: Float,
    val regionMap: RegionMap,
    val denseMap: DenseAnalysisMap
) {
    fun summary(): String = buildString {
        append("Automatic edit complete · ")
        append("Depth ${(dynamicRange * 100).toInt()}% · ")
        append("Hero ${(foregroundHeroLikelihood * 100).toInt()}%")
    }
}
