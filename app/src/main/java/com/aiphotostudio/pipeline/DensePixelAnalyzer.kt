package com.aiphotostudio.pipeline

import android.graphics.Bitmap
import android.graphics.Color
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * Dense pixel-level analysis at optimized analysis resolution.
 *
 * This is non-generative. It does not create/replace pixels. It reads the existing photo
 * into dense feature maps so the planner/scorer can reason about the image in detail.
 */
object DensePixelAnalyzer {
    private const val MAX_ANALYSIS_DIMENSION = 640

    fun analyze(source: Bitmap): DenseAnalysisMap {
        val bitmap = downscale(source)
        val width = bitmap.width
        val height = bitmap.height
        val size = width * height
        val pixels = IntArray(size)
        bitmap.getPixels(pixels, 0, width, 0, 0, width, height)

        val luminance = FloatArray(size)
        val saturation = FloatArray(size)
        val warmth = FloatArray(size)
        val edge = FloatArray(size)
        val texture = FloatArray(size)
        val smoothness = FloatArray(size)
        val highlight = FloatArray(size)
        val shadow = FloatArray(size)
        val skin = FloatArray(size)
        val sky = FloatArray(size)
        val green = FloatArray(size)
        val warmObject = FloatArray(size)
        val saliency = FloatArray(size)
        val distraction = FloatArray(size)

        for (i in 0 until size) {
            val c = pixels[i]
            val r = Color.red(c) / 255f
            val g = Color.green(c) / 255f
            val b = Color.blue(c) / 255f
            val lum = 0.2126f * r + 0.7152f * g + 0.0722f * b
            val mx = max(r, max(g, b))
            val mn = min(r, min(g, b))
            val sat = mx - mn
            luminance[i] = lum
            saturation[i] = sat
            warmth[i] = (r - b).coerceIn(-1f, 1f)
            highlight[i] = smoothstep(0.72f, 0.98f, lum)
            shadow[i] = 1f - smoothstep(0.05f, 0.30f, lum)
            skin[i] = skinLikelihood(r, g, b, lum, sat)
            green[i] = greenLikelihood(r, g, b, lum)
        }

        for (y in 0 until height) {
            for (x in 0 until width) {
                val idx = y * width + x
                val lx = luminance[idx]
                val left = luminance[y * width + max(0, x - 1)]
                val right = luminance[y * width + min(width - 1, x + 1)]
                val up = luminance[max(0, y - 1) * width + x]
                val down = luminance[min(height - 1, y + 1) * width + x]
                val e = (abs(right - left) + abs(down - up)).coerceIn(0f, 1f)
                edge[idx] = (e * 2.2f).coerceIn(0f, 1f)
                val localTexture = (abs(lx - left) + abs(lx - right) + abs(lx - up) + abs(lx - down)) / 4f
                texture[idx] = (localTexture * 5.5f).coerceIn(0f, 1f)
                smoothness[idx] = 1f - texture[idx]

                val nx = x.toFloat() / max(1, width - 1)
                val ny = y.toFloat() / max(1, height - 1)
                val centerBias = (1f - max(abs(nx - 0.5f) * 1.65f, abs(ny - 0.54f) * 1.45f)).coerceIn(0f, 1f)
                sky[idx] = skyLikelihood(ny, luminance[idx], saturation[idx], warmth[idx], texture[idx])
                warmObject[idx] = warmObjectLikelihood(warmth[idx], saturation[idx], luminance[idx], texture[idx], skin[idx])
                saliency[idx] = (edge[idx] * 0.28f + texture[idx] * 0.24f + saturation[idx] * 0.14f + centerBias * 0.28f + abs(luminance[idx] - 0.5f) * 0.06f).coerceIn(0f, 1f)
                val edgeBias = max(abs(nx - 0.5f), abs(ny - 0.5f)) * 2f
                distraction[idx] = (smoothstep(0.62f, 1f, edgeBias) * (highlight[idx] * 0.35f + saturation[idx] * 0.25f + edge[idx] * 0.25f + texture[idx] * 0.15f)).coerceIn(0f, 1f)
            }
        }

        return DenseAnalysisMap(
            width = width,
            height = height,
            luminance = luminance,
            saturation = saturation,
            warmth = warmth,
            edge = edge,
            texture = texture,
            smoothness = smoothness,
            highlight = highlight,
            shadow = shadow,
            skinLikelihood = skin,
            skyLikelihood = sky,
            greenLikelihood = green,
            warmObjectLikelihood = warmObject,
            saliency = saliency,
            distraction = distraction,
            summary = summarize(width, height, luminance, saturation, warmth, edge, texture, smoothness, highlight, shadow, skin, sky, green, warmObject, saliency, distraction)
        )
    }

    private fun downscale(source: Bitmap): Bitmap {
        val largest = max(source.width, source.height)
        if (largest <= MAX_ANALYSIS_DIMENSION) return source
        val scale = MAX_ANALYSIS_DIMENSION.toFloat() / largest.toFloat()
        val w = (source.width * scale).toInt().coerceAtLeast(1)
        val h = (source.height * scale).toInt().coerceAtLeast(1)
        return Bitmap.createScaledBitmap(source, w, h, true)
    }

    private fun summarize(
        width: Int,
        height: Int,
        luminance: FloatArray,
        saturation: FloatArray,
        warmth: FloatArray,
        edge: FloatArray,
        texture: FloatArray,
        smoothness: FloatArray,
        highlight: FloatArray,
        shadow: FloatArray,
        skin: FloatArray,
        sky: FloatArray,
        green: FloatArray,
        warmObject: FloatArray,
        saliency: FloatArray,
        distraction: FloatArray
    ): DenseAnalysisSummary {
        var avgLum = 0f
        var avgSat = 0f
        var avgEdge = 0f
        var avgTexture = 0f
        var skinSum = 0f
        var skySum = 0f
        var greenSum = 0f
        var warmObjectSum = 0f
        var saliencySum = 0f
        var distractionSum = 0f
        var centerSaliency = 0f; var centerCount = 0
        var edgeDistraction = 0f; var edgeCount = 0
        var topSky = 0f; var topCount = 0
        var hotSkin = 0f; var skinCount = 0
        var subjectXWeighted = 0f; var subjectYWeighted = 0f; var subjectWeight = 0f
        var emptyTop = 0f

        for (y in 0 until height) {
            for (x in 0 until width) {
                val i = y * width + x
                avgLum += luminance[i]
                avgSat += saturation[i]
                avgEdge += edge[i]
                avgTexture += texture[i]
                skinSum += skin[i]
                skySum += sky[i]
                greenSum += green[i]
                warmObjectSum += warmObject[i]
                saliencySum += saliency[i]
                distractionSum += distraction[i]
                val nx = x.toFloat() / max(1, width - 1)
                val ny = y.toFloat() / max(1, height - 1)
                val center = abs(nx - 0.5f) < 0.28f && abs(ny - 0.54f) < 0.34f
                if (center) { centerSaliency += saliency[i]; centerCount++ }
                val edgeZone = max(abs(nx - 0.5f), abs(ny - 0.5f)) > 0.42f
                if (edgeZone) { edgeDistraction += distraction[i]; edgeCount++ }
                if (y < height / 3) {
                    topSky += sky[i] * (0.4f + highlight[i] * 0.6f)
                    emptyTop += sky[i] * smoothness[i] * luminance[i]
                    topCount++
                }
                if (skin[i] > 0.35f) {
                    hotSkin += highlight[i]
                    skinCount++
                }
                val subject = saliency[i] * (1f - distraction[i] * 0.45f)
                subjectXWeighted += nx * subject
                subjectYWeighted += ny * subject
                subjectWeight += subject
            }
        }
        val size = max(1, width * height)
        return DenseAnalysisSummary(
            averageLuminance = avgLum / size,
            averageSaturation = avgSat / size,
            averageEdge = avgEdge / size,
            averageTexture = avgTexture / size,
            skinPresence = skinSum / size,
            skyPresence = skySum / size,
            greenPresence = greenSum / size,
            warmObjectPresence = warmObjectSum / size,
            saliencyStrength = saliencySum / size,
            distractionStrength = distractionSum / size,
            centerSaliency = centerSaliency / max(1, centerCount),
            edgeDistraction = edgeDistraction / max(1, edgeCount),
            topSkyPressure = topSky / max(1, topCount),
            hotSkinPressure = hotSkin / max(1, skinCount),
            subjectX = if (subjectWeight > 0f) subjectXWeighted / subjectWeight else 0.5f,
            subjectY = if (subjectWeight > 0f) subjectYWeighted / subjectWeight else 0.5f,
            emptyTopPressure = emptyTop / max(1, topCount)
        )
    }

    private fun skinLikelihood(r: Float, g: Float, b: Float, lum: Float, sat: Float): Float {
        val ordered = if (r > g && g > b) 1f else 0f
        val hueShape = smoothstep(0.04f, 0.22f, r - b) * ordered
        val satShape = (1f - abs(sat - 0.28f) / 0.34f).coerceIn(0f, 1f)
        val lumShape = smoothstep(0.18f, 0.38f, lum) * (1f - smoothstep(0.86f, 0.98f, lum))
        return (hueShape * satShape * lumShape).coerceIn(0f, 1f)
    }

    private fun greenLikelihood(r: Float, g: Float, b: Float, lum: Float): Float {
        return (smoothstep(0.03f, 0.22f, g - max(r, b)) * smoothstep(0.08f, 0.28f, lum)).coerceIn(0f, 1f)
    }

    private fun skyLikelihood(ny: Float, lum: Float, sat: Float, warmth: Float, texture: Float): Float {
        val top = 1f - smoothstep(0.35f, 0.76f, ny)
        val smooth = 1f - texture
        val color = (1f - smoothstep(0.24f, 0.60f, sat)) * (1f - smoothstep(0.05f, 0.35f, warmth))
        val bright = smoothstep(0.42f, 0.82f, lum)
        return (top * smooth * color * bright).coerceIn(0f, 1f)
    }

    private fun warmObjectLikelihood(warmth: Float, sat: Float, lum: Float, texture: Float, skin: Float): Float {
        val warm = smoothstep(0.06f, 0.28f, warmth)
        val satOk = smoothstep(0.08f, 0.28f, sat)
        val lumOk = smoothstep(0.12f, 0.34f, lum) * (1f - smoothstep(0.88f, 1f, lum))
        val material = texture * (1f - skin * 0.70f)
        return (warm * satOk * lumOk * (0.30f + material * 0.70f)).coerceIn(0f, 1f)
    }

    private fun smoothstep(edge0: Float, edge1: Float, x: Float): Float {
        val t = ((x - edge0) / (edge1 - edge0)).coerceIn(0f, 1f)
        return t * t * (3f - 2f * t)
    }
}

data class DenseAnalysisMap(
    val width: Int,
    val height: Int,
    val luminance: FloatArray,
    val saturation: FloatArray,
    val warmth: FloatArray,
    val edge: FloatArray,
    val texture: FloatArray,
    val smoothness: FloatArray,
    val highlight: FloatArray,
    val shadow: FloatArray,
    val skinLikelihood: FloatArray,
    val skyLikelihood: FloatArray,
    val greenLikelihood: FloatArray,
    val warmObjectLikelihood: FloatArray,
    val saliency: FloatArray,
    val distraction: FloatArray,
    val summary: DenseAnalysisSummary
)

data class DenseAnalysisSummary(
    val averageLuminance: Float,
    val averageSaturation: Float,
    val averageEdge: Float,
    val averageTexture: Float,
    val skinPresence: Float,
    val skyPresence: Float,
    val greenPresence: Float,
    val warmObjectPresence: Float,
    val saliencyStrength: Float,
    val distractionStrength: Float,
    val centerSaliency: Float,
    val edgeDistraction: Float,
    val topSkyPressure: Float,
    val hotSkinPressure: Float,
    val subjectX: Float,
    val subjectY: Float,
    val emptyTopPressure: Float
)
