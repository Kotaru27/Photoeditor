package com.aiphotostudio.pipeline

import android.graphics.Bitmap
import android.graphics.Color
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * Region-level image understanding.
 * Non-generative: reads existing pixels only and builds a map of light/color/detail/distraction.
 */
object RegionMapAnalyzer {
    private const val GRID = 12

    fun analyze(bitmap: Bitmap): RegionMap {
        val width = bitmap.width
        val height = bitmap.height
        val pixels = IntArray(width * height)
        bitmap.getPixels(pixels, 0, width, 0, 0, width, height)
        val cells = mutableListOf<RegionCell>()

        for (gy in 0 until GRID) {
            for (gx in 0 until GRID) {
                val x0 = gx * width / GRID
                val x1 = ((gx + 1) * width / GRID).coerceAtMost(width)
                val y0 = gy * height / GRID
                val y1 = ((gy + 1) * height / GRID).coerceAtMost(height)
                cells += analyzeCell(pixels, width, height, gx, gy, x0, y0, x1, y1)
            }
        }

        val centerCells = cells.filter { it.normalizedDistanceFromCenter < 0.42f }
        val edgeCells = cells.filter { it.normalizedDistanceFromCenter > 0.68f }
        val topCells = cells.filter { it.gridY <= 3 }
        val lowerCells = cells.filter { it.gridY >= 7 }

        val centerSaliency = centerCells.avg { it.saliency }
        val edgeDistraction = edgeCells.avg { it.distraction }
        val skyPressure = topCells.avg { it.skyLikelihood * (0.5f + it.highlightPressure * 0.5f) }
        val textureOpportunity = centerCells.avg { it.texture * (1f - it.noiseRisk * 0.55f) }
        val portraitConfidence = cells.avg { it.skinLikelihood } * 0.55f + centerCells.avg { it.skinLikelihood } * 0.45f
        val ornateObjectConfidence = centerCells.avg { it.warmObjectLikelihood * 0.55f + it.texture * 0.30f + it.saliency * 0.15f }
        val greenDominance = cells.avg { it.greenLikelihood }
        val subjectCell = cells.maxByOrNull { it.saliency - it.distraction * 0.25f }
        val subjectX = subjectCell?.centerX ?: 0.5f
        val subjectY = subjectCell?.centerY ?: 0.5f
        val emptyTopPressure = topCells.avg { (1f - it.texture) * it.luminance * it.skyLikelihood }
        val foregroundWeight = lowerCells.avg { it.saliency + it.texture * 0.25f }

        return RegionMap(
            gridSize = GRID,
            cells = cells,
            centerSaliency = centerSaliency.coerceIn(0f, 1f),
            edgeDistraction = edgeDistraction.coerceIn(0f, 1f),
            skyPressure = skyPressure.coerceIn(0f, 1f),
            portraitConfidence = portraitConfidence.coerceIn(0f, 1f),
            ornateObjectConfidence = ornateObjectConfidence.coerceIn(0f, 1f),
            greenDominance = greenDominance.coerceIn(0f, 1f),
            textureOpportunity = textureOpportunity.coerceIn(0f, 1f),
            subjectX = subjectX.coerceIn(0f, 1f),
            subjectY = subjectY.coerceIn(0f, 1f),
            emptyTopPressure = emptyTopPressure.coerceIn(0f, 1f),
            foregroundWeight = foregroundWeight.coerceIn(0f, 1f)
        )
    }

    private fun analyzeCell(
        pixels: IntArray,
        width: Int,
        height: Int,
        gx: Int,
        gy: Int,
        x0: Int,
        y0: Int,
        x1: Int,
        y1: Int
    ): RegionCell {
        val stepX = max(1, (x1 - x0) / 12)
        val stepY = max(1, (y1 - y0) / 12)
        var count = 0
        var lumSum = 0.0
        var satSum = 0.0
        var warmthSum = 0.0
        var textureSum = 0.0
        var highlight = 0
        var shadow = 0
        var sky = 0
        var skin = 0
        var green = 0
        var warmObject = 0
        var previousLum = -1f

        var y = y0
        while (y < y1) {
            var x = x0
            while (x < x1) {
                val c = pixels[y * width + x]
                val r = Color.red(c) / 255f
                val g = Color.green(c) / 255f
                val b = Color.blue(c) / 255f
                val lum = 0.2126f * r + 0.7152f * g + 0.0722f * b
                val mx = max(r, max(g, b))
                val mn = min(r, min(g, b))
                val sat = mx - mn
                val warmth = (r - b).coerceIn(-1f, 1f)

                lumSum += lum
                satSum += sat
                warmthSum += warmth
                if (previousLum >= 0f) textureSum += abs(lum - previousLum)
                previousLum = lum
                if (lum > 0.90f) highlight++
                if (lum < 0.08f) shadow++
                if (gy <= 4 && lum > 0.48f && b >= r - 0.08f && sat < 0.34f) sky++
                if (r > g && g > b && r - b > 0.07f && lum in 0.24f..0.88f && sat in 0.08f..0.58f) skin++
                if (g > r + 0.04f && g > b + 0.04f && lum > 0.15f) green++
                if (warmth > 0.045f && sat > 0.055f && lum in 0.12f..0.88f) warmObject++
                count++
                x += stepX
            }
            y += stepY
        }

        val safe = max(1, count)
        val luminance = (lumSum / safe).toFloat()
        val saturation = (satSum / safe).toFloat()
        val warmth = (warmthSum / safe).toFloat()
        val texture = (textureSum / safe / 0.18).toFloat().coerceIn(0f, 1f)
        val highlightPressure = highlight.toFloat() / safe
        val shadowPressure = shadow.toFloat() / safe
        val skyLikelihood = sky.toFloat() / safe
        val skinLikelihood = skin.toFloat() / safe
        val greenLikelihood = green.toFloat() / safe
        val warmObjectLikelihood = (warmObject.toFloat() / safe * (0.45f + texture * 0.55f)).coerceIn(0f, 1f)
        val centerX = (gx + 0.5f) / GRID
        val centerY = (gy + 0.5f) / GRID
        val dx = abs(centerX - 0.5f) * 2f
        val dy = abs(centerY - 0.54f) * 2f
        val distance = max(dx, dy).coerceIn(0f, 1f)
        val centerBias = 1f - distance
        val saliency = (texture * 0.34f + saturation * 0.20f + abs(luminance - 0.5f) * 0.12f + centerBias * 0.34f).coerceIn(0f, 1f)
        val edgeBias = if (distance > 0.68f) 1f else 0f
        val distraction = (edgeBias * (highlightPressure * 0.35f + saturation * 0.25f + texture * 0.25f) + (1f - centerBias) * 0.15f).coerceIn(0f, 1f)
        val noiseRisk = (texture * shadowPressure * 1.4f).coerceIn(0f, 1f)

        return RegionCell(
            gridX = gx,
            gridY = gy,
            centerX = centerX,
            centerY = centerY,
            luminance = luminance,
            saturation = saturation,
            warmth = warmth,
            texture = texture,
            highlightPressure = highlightPressure,
            shadowPressure = shadowPressure,
            skyLikelihood = skyLikelihood,
            skinLikelihood = skinLikelihood,
            greenLikelihood = greenLikelihood,
            warmObjectLikelihood = warmObjectLikelihood,
            saliency = saliency,
            distraction = distraction,
            noiseRisk = noiseRisk,
            normalizedDistanceFromCenter = distance
        )
    }

    private fun List<RegionCell>.avg(block: (RegionCell) -> Float): Float {
        if (isEmpty()) return 0f
        var sum = 0f
        forEach { sum += block(it) }
        return sum / size
    }
}

data class RegionMap(
    val gridSize: Int,
    val cells: List<RegionCell>,
    val centerSaliency: Float,
    val edgeDistraction: Float,
    val skyPressure: Float,
    val portraitConfidence: Float,
    val ornateObjectConfidence: Float,
    val greenDominance: Float,
    val textureOpportunity: Float,
    val subjectX: Float,
    val subjectY: Float,
    val emptyTopPressure: Float,
    val foregroundWeight: Float
)

data class RegionCell(
    val gridX: Int,
    val gridY: Int,
    val centerX: Float,
    val centerY: Float,
    val luminance: Float,
    val saturation: Float,
    val warmth: Float,
    val texture: Float,
    val highlightPressure: Float,
    val shadowPressure: Float,
    val skyLikelihood: Float,
    val skinLikelihood: Float,
    val greenLikelihood: Float,
    val warmObjectLikelihood: Float,
    val saliency: Float,
    val distraction: Float,
    val noiseRisk: Float,
    val normalizedDistanceFromCenter: Float
)
