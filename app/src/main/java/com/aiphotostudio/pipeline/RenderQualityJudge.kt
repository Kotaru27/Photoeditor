package com.aiphotostudio.pipeline

import android.graphics.Bitmap
import com.aiphotostudio.editgraph.ColorOperation
import com.aiphotostudio.editgraph.DetailOperation
import com.aiphotostudio.editgraph.EditGraph
import com.aiphotostudio.editgraph.LocalLightOperation
import com.aiphotostudio.editgraph.ToneOperation
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * Automatic quality safety system.
 * It does not edit creatively. It checks whether the automatic edit went too far,
 * then asks the same renderer to re-render with a safer EditGraph.
 */
object RenderQualityJudge {
    fun renderWithSafety(source: Bitmap, graph: EditGraph): QualityRenderResult {
        val first = LocalGradingRenderer.render(source, graph)
        val sourceStats = measure(source)
        val editedStats = measure(first)
        val report = judge(sourceStats, editedStats)

        if (report.passed) {
            return QualityRenderResult(first, graph, report)
        }

        val saferGraph = reduceIntensity(graph, report.reductionStrength)
        val saferBitmap = LocalGradingRenderer.render(source, saferGraph)
        val saferReport = judge(sourceStats, measure(saferBitmap)).copy(wasAutoReduced = true)
        return QualityRenderResult(saferBitmap, saferGraph, saferReport)
    }

    private fun judge(original: ImageStats, edited: ImageStats): QualityReport {
        val warnings = mutableListOf<String>()
        var score = 100
        var reduction = 0f

        if (edited.highlightClip > max(0.055f, original.highlightClip + 0.040f)) {
            warnings += "Highlights were getting too harsh"
            score -= 18
            reduction = max(reduction, 0.18f)
        }
        if (edited.shadowClip > max(0.075f, original.shadowClip + 0.045f)) {
            warnings += "Shadows were getting crushed"
            score -= 18
            reduction = max(reduction, 0.18f)
        }
        if (edited.averageLuminance > 0.82f && edited.averageLuminance - original.averageLuminance > 0.18f) {
            warnings += "Edit became too bright"
            score -= 14
            reduction = max(reduction, 0.18f)
        }
        if (edited.averageLuminance < 0.18f && original.averageLuminance - edited.averageLuminance > 0.16f) {
            warnings += "Edit became too dark"
            score -= 14
            reduction = max(reduction, 0.18f)
        }
        if (edited.saturation > max(0.56f, original.saturation + 0.24f)) {
            warnings += "Color was becoming too saturated"
            score -= 16
            reduction = max(reduction, 0.22f)
        }
        if (edited.localContrast > original.localContrast + 0.32f && edited.highlightClip > original.highlightClip + 0.028f) {
            warnings += "Edit was moving toward fake HDR"
            score -= 22
            reduction = max(reduction, 0.32f)
        }
        if (edited.topHighlightPressure > max(0.10f, original.topHighlightPressure + 0.055f)) {
            warnings += "Top highlights were becoming distracting"
            score -= 16
            reduction = max(reduction, 0.18f)
        }
        if (edited.skinHotPressure > max(0.09f, original.skinHotPressure + 0.040f)) {
            warnings += "Skin-like highlights were getting too hot"
            score -= 20
            reduction = max(reduction, 0.24f)
        }
        if (edited.edgeDarkness > original.edgeDarkness + 0.16f && edited.averageLuminance < original.averageLuminance - 0.04f) {
            warnings += "Vignette/edge darkening was too heavy"
            score -= 12
            reduction = max(reduction, 0.14f)
        }
        if (abs(edited.averageLuminance - original.averageLuminance) < 0.018f &&
            abs(edited.saturation - original.saturation) < 0.018f &&
            abs(edited.localContrast - original.localContrast) < 0.018f) {
            warnings += "Edit may be too weak"
            score -= 6
        }

        return QualityReport(
            score = score.coerceIn(0, 100),
            passed = score >= 70 && reduction <= 0.001f,
            warnings = warnings,
            reductionStrength = reduction.coerceIn(0f, 0.45f),
            wasAutoReduced = false
        )
    }

    private fun reduceIntensity(graph: EditGraph, strength: Float): EditGraph {
        val keep = (1f - strength).coerceIn(0.68f, 1f)
        return graph.copy(
            professionalIntent = graph.professionalIntent,
            intentReason = graph.intentReason + " Safety check applied restraint to keep the image natural.",
            tone = ToneOperation(
                exposure = graph.tone.exposure * keep,
                contrast = graph.tone.contrast * keep,
                highlights = graph.tone.highlights * keep,
                shadows = graph.tone.shadows * keep,
                whites = graph.tone.whites * keep,
                blacks = graph.tone.blacks * keep,
                gamma = 1f + (graph.tone.gamma - 1f) * keep,
                curveStrength = graph.tone.curveStrength * keep,
                midtoneLift = graph.tone.midtoneLift * keep,
                highlightSoftness = graph.tone.highlightSoftness
            ),
            color = ColorOperation(
                temperature = graph.color.temperature * keep,
                tint = graph.color.tint * keep,
                vibrance = graph.color.vibrance * keep,
                saturation = graph.color.saturation * keep,
                colorSeparation = graph.color.colorSeparation * keep,
                memoryColorProtect = graph.color.memoryColorProtect
            ),
            detail = DetailOperation(
                clarity = graph.detail.clarity * keep,
                texture = graph.detail.texture * keep,
                sharpness = graph.detail.sharpness * keep,
                noiseReduction = graph.detail.noiseReduction,
                dehaze = graph.detail.dehaze * keep,
                haloGuard = graph.detail.haloGuard
            ),
            local = LocalLightOperation(
                subjectLift = graph.local.subjectLift * keep,
                subjectContrast = graph.local.subjectContrast * keep,
                backgroundCalm = graph.local.backgroundCalm * keep,
                skyRecovery = graph.local.skyRecovery,
                foregroundDepth = graph.local.foregroundDepth * keep,
                edgeVignette = graph.local.edgeVignette * keep,
                centerFocus = graph.local.centerFocus * keep,
                heroColorRichness = graph.local.heroColorRichness * keep
            )
        )
    }

    private fun measure(bitmap: Bitmap): ImageStats {
        val width = bitmap.width
        val height = bitmap.height
        val step = max(1, max(width, height) / 800)
        val pixels = IntArray(width * height)
        bitmap.getPixels(pixels, 0, width, 0, 0, width, height)
        var count = 0
        var lumSum = 0.0
        var satSum = 0.0
        var shadowClip = 0
        var highlightClip = 0
        var localContrast = 0.0
        var topHighlight = 0
        var topCount = 0
        var skinHot = 0
        var skinCount = 0
        var edgeDarkSum = 0.0
        var edgeCount = 0
        var previousLum = -1f

        var y = 0
        while (y < height) {
            var x = 0
            while (x < width) {
                val c = pixels[y * width + x]
                val r = ((c shr 16) and 255) / 255f
                val g = ((c shr 8) and 255) / 255f
                val b = (c and 255) / 255f
                val lum = 0.2126f * r + 0.7152f * g + 0.0722f * b
                val mx = max(r, max(g, b))
                val mn = min(r, min(g, b))
                lumSum += lum
                satSum += (mx - mn)
                if (lum <= 0.018f) shadowClip++
                if (lum >= 0.985f) highlightClip++
                if (y < height / 3) {
                    topCount++
                    if (lum > 0.86f) topHighlight++
                }
                val skinLike = r > g && g > b && r - b > 0.07f && lum > 0.28f
                if (skinLike) {
                    skinCount++
                    if (lum > 0.72f) skinHot++
                }
                val nx = x.toFloat() / width.toFloat()
                val ny = y.toFloat() / height.toFloat()
                if (kotlin.math.max(abs(nx - 0.5f), abs(ny - 0.5f)) > 0.42f) {
                    edgeCount++
                    edgeDarkSum += 1.0 - lum
                }
                if (previousLum >= 0f) localContrast += abs(lum - previousLum)
                previousLum = lum
                count++
                x += step
            }
            y += step
        }

        val safeCount = max(1, count)
        return ImageStats(
            averageLuminance = (lumSum / safeCount).toFloat(),
            saturation = (satSum / safeCount).toFloat(),
            shadowClip = shadowClip.toFloat() / safeCount,
            highlightClip = highlightClip.toFloat() / safeCount,
            localContrast = (localContrast / safeCount).toFloat(),
            topHighlightPressure = topHighlight.toFloat() / max(1, topCount),
            skinHotPressure = skinHot.toFloat() / max(1, skinCount),
            edgeDarkness = (edgeDarkSum / max(1, edgeCount)).toFloat()
        )
    }
}

data class QualityRenderResult(
    val bitmap: Bitmap,
    val graph: EditGraph,
    val report: QualityReport
)

data class QualityReport(
    val score: Int,
    val passed: Boolean,
    val warnings: List<String>,
    val reductionStrength: Float,
    val wasAutoReduced: Boolean
) {
    fun userMessage(): String {
        return if (wasAutoReduced) {
            "Automatic edit refined"
        } else {
            "Automatic edit ready"
        }
    }
}

private data class ImageStats(
    val averageLuminance: Float,
    val saturation: Float,
    val shadowClip: Float,
    val highlightClip: Float,
    val localContrast: Float,
    val topHighlightPressure: Float,
    val skinHotPressure: Float,
    val edgeDarkness: Float
)
