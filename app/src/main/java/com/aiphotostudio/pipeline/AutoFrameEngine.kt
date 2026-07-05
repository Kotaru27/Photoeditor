package com.aiphotostudio.pipeline

import com.aiphotostudio.editgraph.GeometryOperation
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * Auto Frame 2.3: forced-safe empty top crop (v1.4.3: relaxed detection + forced apply).
 *
 * Uses subject bounds + empty-space pressure, plus a direct top empty-band detector.
 * The goal is not a fixed crop, but a crop that improves each image's composition
 * while preserving the subject.
 */
object AutoFrameEngine {
    fun plan(analysis: AnalysisResult, profile: SceneUnderstandingProfile): GeometryOperation {
        val dense = analysis.denseMap
        val bounds = estimateSubjectBounds(dense, profile)
        val empty = estimateEmptySpace(dense)
        val emptyBand = detectEmptyTopBand(dense, bounds)
        val objectScene = profile.shouldEnhanceObjectMaterial || profile.dominant == SceneDominant.OBJECT_MATERIAL
        val portraitSafe = profile.shouldProtectSkin || profile.dominant == SceneDominant.PERSON
        val skyPressure = maxOf(profile.skyHeavyLikelihood, dense.summary.topSkyPressure, analysis.regionMap.skyPressure)
        val subjectCenterY = (bounds.top + bounds.bottom) / 2f

        val candidates = mutableListOf(CropCandidate(0f, 0f, 1f, 1f))
        val topOptions = when {
            portraitSafe -> listOf(0.02f, 0.04f, 0.06f, 0.08f)
            objectScene && empty.topPressure > 0.18f && subjectCenterY > 0.40f -> listOf(0.08f, 0.12f, 0.16f, 0.20f, 0.24f, 0.28f)
            skyPressure > 0.28f || empty.topPressure > 0.20f -> listOf(0.06f, 0.10f, 0.14f, 0.18f, 0.22f)
            else -> listOf(0.04f, 0.08f, 0.12f)
        }
        for (top in topOptions) {
            candidates += CropCandidate(0f, top, 1f, 1f)
            if (!portraitSafe && (profile.backgroundClutter > 0.20f || empty.sidePressure > 0.15f)) {
                candidates += CropCandidate(0.018f, top, 0.982f, 1f)
                candidates += CropCandidate(0.035f, top, 0.965f, 1f)
            }
        }
        if (!portraitSafe && emptyBand.found) {
            candidates += CropCandidate(0f, emptyBand.bandEnd, 1f, 1f)
        }

        val scored = candidates.map { it to scoreCandidate(it, bounds, empty, analysis, profile) }
        val originalScore = scored.first { it.first.top == 0f && it.first.left == 0f }.second
        val best = scored.maxByOrNull { it.second } ?: scored.first()

        val decisiveScene = (objectScene && empty.topPressure > 0.16f && bounds.top > 0.24f) ||
            (!portraitSafe && skyPressure > 0.34f && bounds.top > 0.22f) ||
            (!portraitSafe && emptyBand.found)
        val threshold = if (decisiveScene) 0.015f else 0.055f
        var chosen = if (best.second > originalScore + threshold) best.first else candidates.first()

        // v1.4.3 forced-safe framing: if a clear removable empty top band is detected and
        // the subject is safely below it, apply the crop directly instead of letting
        // scoring hesitate. This targets the repeated failure where white/gray sky
        // stayed in the image.
        if (!portraitSafe && emptyBand.found && chosen.top < emptyBand.bandEnd) {
            val forced = CropCandidate(0f, emptyBand.bandEnd, 1f, 1f)
            val subjectSafeBelowBand = bounds.top >= emptyBand.bandEnd + 0.02f
            if (subjectSafeBelowBand && scoreCandidate(forced, bounds, empty, analysis, profile) > -6f) {
                chosen = forced
            }
        }

        return GeometryOperation(
            cropLeft = chosen.left.coerceIn(0f, 0.18f),
            cropTop = chosen.top.coerceIn(0f, if (portraitSafe) 0.10f else 0.30f),
            cropRight = chosen.right.coerceIn(0.82f, 1f),
            cropBottom = chosen.bottom.coerceIn(0.82f, 1f)
        )
    }

    /**
     * Scans the top of the image row-by-row to find a continuous removable band that is
     * bright, smooth, low texture, low subject, low warm-object, and low skin. This
     * specifically targets white/gray sky, blank wall, and empty top-space failures.
     */
    private fun detectEmptyTopBand(dense: DenseAnalysisMap, bounds: SubjectBounds): EmptyBandResult {
        // v1.4.3: relaxed thresholds so faint wires/cloud texture/compression noise
        // do not prevent top empty-space detection as easily.
        val rowEmptyThreshold = 0.34f
        val rowSubjectThreshold = 0.22f
        val rowTextureThreshold = 0.40f
        val maxBandFraction = min(0.28f, (bounds.top - 0.02f).coerceAtLeast(0f))
        if (maxBandFraction < 0.06f) return EmptyBandResult(false, 0f)

        var bandEnd = 0f
        for (y in 0 until dense.height) {
            val ny = y.toFloat() / (dense.height - 1).coerceAtLeast(1)
            if (ny > maxBandFraction) break

            var rowLum = 0f
            var rowSmooth = 0f
            var rowSubject = 0f
            var rowTexture = 0f
            var rowWarm = 0f
            var rowSkin = 0f
            for (x in 0 until dense.width) {
                val i = y * dense.width + x
                rowLum += dense.luminance[i]
                rowSmooth += dense.smoothness[i]
                rowSubject += dense.saliency[i]
                rowTexture += dense.texture[i]
                rowWarm += dense.warmObjectLikelihood[i]
                rowSkin += dense.skinLikelihood[i]
            }
            val w = dense.width.toFloat()
            rowLum /= w; rowSmooth /= w; rowSubject /= w; rowTexture /= w; rowWarm /= w; rowSkin /= w

            val rowEmptyScore = rowLum * rowSmooth * (1f - rowSubject * 0.7f) * (1f - rowWarm * 0.8f) * (1f - rowSkin * 0.8f)
            val rowIsEmpty = rowEmptyScore > rowEmptyThreshold &&
                rowSubject < rowSubjectThreshold &&
                rowTexture < rowTextureThreshold

            if (rowIsEmpty) {
                bandEnd = ny
            } else {
                break
            }
        }
        return EmptyBandResult(bandEnd >= 0.06f, bandEnd)
    }

    private fun scoreCandidate(c: CropCandidate, b: SubjectBounds, e: EmptySpaceProfile, a: AnalysisResult, p: SceneUnderstandingProfile): Float {
        val cropW = c.right - c.left
        val cropH = c.bottom - c.top
        if (cropW <= 0.58f || cropH <= 0.58f) return -10f
        if (!(c.left < b.left && c.right > b.right && c.top < b.top && c.bottom > b.bottom)) return -8f

        val topMargin = b.top - c.top
        val bottomMargin = c.bottom - b.bottom
        val leftMargin = b.left - c.left
        val rightMargin = c.right - b.right
        val safety = min(min(topMargin, bottomMargin), min(leftMargin, rightMargin))
        if (safety < 0.018f) return -5f

        val subjectArea = (b.right - b.left) * (b.bottom - b.top)
        val subjectScaleGain = (subjectArea / (cropW * cropH) - subjectArea).coerceIn(0f, 0.55f)
        val removedTop = c.top
        val removedSides = c.left + (1f - c.right)
        val subjectYAfter = (((b.top + b.bottom) / 2f) - c.top) / cropH
        val balance = 1f - minOf(abs(subjectYAfter - 0.56f), abs(subjectYAfter - 0.45f), abs(subjectYAfter - 0.50f))
        val skyPressure = maxOf(a.regionMap.skyPressure, a.denseMap.summary.topSkyPressure, p.skyHeavyLikelihood)

        var score = 0f
        score += subjectScaleGain * 3.4f
        score += removedTop * e.topPressure * 5.0f
        score += removedTop * skyPressure * 3.0f
        score += removedSides * max(p.backgroundClutter, e.sidePressure) * 2.2f
        score += balance * 0.20f
        score += safety.coerceIn(0f, 0.14f) * 0.55f

        if (p.shouldProtectSkin && removedTop > 0.08f) score -= 0.90f
        if (p.dominant == SceneDominant.SKY_LANDSCAPE && removedTop > 0.14f) score -= 0.55f
        if (removedTop > 0.22f && e.topPressure < 0.22f) score -= 0.45f
        if (subjectYAfter < 0.30f || subjectYAfter > 0.78f) score -= 0.25f
        return score
    }

    private fun estimateSubjectBounds(dense: DenseAnalysisMap, profile: SceneUnderstandingProfile): SubjectBounds {
        var maxSubject = 0f
        val weights = FloatArray(dense.width * dense.height)
        for (y in 0 until dense.height) {
            for (x in 0 until dense.width) {
                val i = y * dense.width + x
                val ny = y.toFloat() / (dense.height - 1).coerceAtLeast(1)
                val foreground = smoothstep(0.25f, 0.92f, ny)
                val objectTerm = dense.warmObjectLikelihood[i] * (0.6f + dense.texture[i] * 0.6f)
                val portraitTerm = dense.skinLikelihood[i] * (0.65f + dense.saliency[i] * 0.35f)
                val generic = dense.saliency[i] * (1f - dense.skyLikelihood[i] * 0.95f) * (1f - dense.distraction[i] * 0.30f)
                val w = when {
                    profile.shouldEnhanceObjectMaterial -> objectTerm * 0.55f + generic * 0.35f + foreground * 0.10f
                    profile.shouldProtectSkin -> portraitTerm * 0.55f + generic * 0.35f + foreground * 0.10f
                    else -> generic * 0.75f + objectTerm * 0.15f + portraitTerm * 0.10f
                }.coerceIn(0f, 1f)
                weights[i] = w
                if (w > maxSubject) maxSubject = w
            }
        }
        val threshold = (maxSubject * 0.46f).coerceAtLeast(0.10f)
        var minX = 1f; var minY = 1f; var maxX = 0f; var maxY = 0f; var found = false
        for (y in 0 until dense.height) {
            for (x in 0 until dense.width) {
                val i = y * dense.width + x
                if (weights[i] >= threshold) {
                    val nx = x.toFloat() / (dense.width - 1).coerceAtLeast(1)
                    val ny = y.toFloat() / (dense.height - 1).coerceAtLeast(1)
                    minX = min(minX, nx); maxX = max(maxX, nx)
                    minY = min(minY, ny); maxY = max(maxY, ny)
                    found = true
                }
            }
        }
        if (!found) {
            val cx = dense.summary.subjectX
            val cy = dense.summary.subjectY
            return SubjectBounds((cx - 0.22f).coerceIn(0f, 1f), (cy - 0.22f).coerceIn(0f, 1f), (cx + 0.22f).coerceIn(0f, 1f), (cy + 0.26f).coerceIn(0f, 1f))
        }
        val padX = if (profile.shouldProtectSkin) 0.08f else 0.055f
        val padTop = if (profile.shouldProtectSkin) 0.10f else 0.055f
        val padBottom = if (profile.shouldProtectSkin) 0.08f else 0.06f
        return SubjectBounds((minX - padX).coerceIn(0f, 1f), (minY - padTop).coerceIn(0f, 1f), (maxX + padX).coerceIn(0f, 1f), (maxY + padBottom).coerceIn(0f, 1f))
    }

    private fun estimateEmptySpace(dense: DenseAnalysisMap): EmptySpaceProfile {
        var top = 0f; var topCount = 0
        var side = 0f; var sideCount = 0
        for (y in 0 until dense.height) {
            for (x in 0 until dense.width) {
                val i = y * dense.width + x
                val nx = x.toFloat() / (dense.width - 1).coerceAtLeast(1)
                val ny = y.toFloat() / (dense.height - 1).coerceAtLeast(1)
                val empty = dense.luminance[i] * dense.smoothness[i] * (1f - dense.saliency[i] * 0.75f) * (1f - dense.warmObjectLikelihood[i] * 0.85f) * (1f - dense.skinLikelihood[i] * 0.85f)
                if (ny < 0.36f) { top += empty; topCount++ }
                if (nx < 0.12f || nx > 0.88f) { side += empty + dense.distraction[i] * 0.35f; sideCount++ }
            }
        }
        return EmptySpaceProfile((top / max(1, topCount)).coerceIn(0f, 1f), (side / max(1, sideCount)).coerceIn(0f, 1f))
    }

    private fun smoothstep(edge0: Float, edge1: Float, x: Float): Float {
        val t = ((x - edge0) / (edge1 - edge0)).coerceIn(0f, 1f)
        return t * t * (3f - 2f * t)
    }
}

private data class CropCandidate(val left: Float, val top: Float, val right: Float, val bottom: Float)
private data class SubjectBounds(val left: Float, val top: Float, val right: Float, val bottom: Float)
private data class EmptySpaceProfile(val topPressure: Float, val sidePressure: Float)
private data class EmptyBandResult(val found: Boolean, val bandEnd: Float)
