package com.aiphotostudio.pipeline

import com.aiphotostudio.editgraph.GeometryOperation
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * Auto Frame 2.2: decisive empty-band aware framing.
 *
 * Fixes the failure where large bright blank top areas were kept because generic saliency
 * made subject bounds too broad. This version directly detects removable top empty bands
 * and then validates subject safety.
 */
object AutoFrameEngine {
    fun plan(analysis: AnalysisResult, profile: SceneUnderstandingProfile): GeometryOperation {
        val dense = analysis.denseMap
        val objectScene = profile.shouldEnhanceObjectMaterial || profile.dominant == SceneDominant.OBJECT_MATERIAL
        val portraitSafe = profile.shouldProtectSkin || profile.dominant == SceneDominant.PERSON
        val bounds = estimateSubjectBounds(dense, profile)
        val emptyBandTop = estimateTopEmptyBand(dense, profile)
        val empty = estimateEmptySpace(dense)
        val skyPressure = maxOf(profile.skyHeavyLikelihood, dense.summary.topSkyPressure, analysis.regionMap.skyPressure)

        val candidates = mutableListOf(CropCandidate(0f, 0f, 1f, 1f))

        val directTop = when {
            portraitSafe -> min(emptyBandTop, 0.08f)
            objectScene && emptyBandTop > 0.10f -> emptyBandTop.coerceIn(0.10f, 0.30f)
            skyPressure > 0.32f && emptyBandTop > 0.08f -> emptyBandTop.coerceIn(0.08f, 0.24f)
            else -> 0f
        }

        if (directTop > 0f) {
            candidates += CropCandidate(0f, directTop * 0.70f, 1f, 1f)
            candidates += CropCandidate(0f, directTop, 1f, 1f)
            candidates += CropCandidate(0f, (directTop * 1.15f).coerceAtMost(if (portraitSafe) 0.10f else 0.32f), 1f, 1f)
            if (!portraitSafe && profile.backgroundClutter > 0.18f) {
                candidates += CropCandidate(0.02f, directTop, 0.98f, 1f)
                candidates += CropCandidate(0.035f, directTop, 0.965f, 1f)
            }
        }

        // Keep fallback candidates for non-empty-band scenes.
        val fallbackTops = if (portraitSafe) listOf(0.03f, 0.05f, 0.07f) else listOf(0.08f, 0.12f, 0.16f, 0.20f, 0.24f)
        for (top in fallbackTops) {
            if (top < bounds.top - 0.035f) candidates += CropCandidate(0f, top, 1f, 1f)
        }

        val scored = candidates.distinct().map { it to scoreCandidate(it, bounds, empty, analysis, profile, emptyBandTop) }
        val original = scored.first { it.first.top == 0f && it.first.left == 0f }
        val best = scored.maxByOrNull { it.second } ?: original

        val decisive = !portraitSafe && emptyBandTop > 0.12f && bounds.top > emptyBandTop + 0.035f
        val threshold = if (decisive) -0.02f else 0.035f
        val chosen = if (best.second > original.second + threshold) best.first else CropCandidate(0f, 0f, 1f, 1f)

        return GeometryOperation(
            cropLeft = chosen.left.coerceIn(0f, 0.18f),
            cropTop = chosen.top.coerceIn(0f, if (portraitSafe) 0.10f else 0.34f),
            cropRight = chosen.right.coerceIn(0.82f, 1f),
            cropBottom = chosen.bottom.coerceIn(0.82f, 1f)
        )
    }

    /**
     * Finds a continuous top band that is bright/smooth/low-subject and therefore likely
     * removable empty space. This is the key fix for white/gray sky or blank wall areas.
     */
    private fun estimateTopEmptyBand(dense: DenseAnalysisMap, profile: SceneUnderstandingProfile): Float {
        val maxRows = (dense.height * 0.42f).toInt().coerceAtLeast(1)
        var lastEmptyRow = -1
        var consecutiveContent = 0
        for (y in 0 until maxRows) {
            var rowEmpty = 0f
            var rowSubject = 0f
            var rowTexture = 0f
            for (x in 0 until dense.width) {
                val i = y * dense.width + x
                val empty = dense.luminance[i] * dense.smoothness[i] *
                    (1f - dense.saliency[i] * 0.85f) *
                    (1f - dense.warmObjectLikelihood[i] * 0.95f) *
                    (1f - dense.skinLikelihood[i] * 0.95f)
                val subject = when {
                    profile.shouldEnhanceObjectMaterial -> dense.warmObjectLikelihood[i] * 0.55f + dense.saliency[i] * 0.25f + dense.texture[i] * 0.20f
                    profile.shouldProtectSkin -> dense.skinLikelihood[i] * 0.65f + dense.saliency[i] * 0.35f
                    else -> dense.saliency[i]
                }
                rowEmpty += empty
                rowSubject += subject
                rowTexture += dense.texture[i]
            }
            rowEmpty /= dense.width
            rowSubject /= dense.width
            rowTexture /= dense.width

            val isEmpty = rowEmpty > 0.34f && rowSubject < 0.18f && rowTexture < 0.30f
            if (isEmpty) {
                lastEmptyRow = y
                consecutiveContent = 0
            } else {
                consecutiveContent++
                if (consecutiveContent >= 5 && lastEmptyRow >= 0) break
            }
        }
        if (lastEmptyRow < 0) return 0f
        // Leave a little breathing room below the empty band.
        return ((lastEmptyRow + 1).toFloat() / dense.height - 0.025f).coerceIn(0f, 0.34f)
    }

    private fun scoreCandidate(c: CropCandidate, b: SubjectBounds, e: EmptySpaceProfile, a: AnalysisResult, p: SceneUnderstandingProfile, emptyBandTop: Float): Float {
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
        score += subjectScaleGain * 3.8f
        score += removedTop * e.topPressure * 5.8f
        score += removedTop * skyPressure * 3.5f
        score += min(removedTop, emptyBandTop) * 3.6f
        score += removedSides * max(p.backgroundClutter, e.sidePressure) * 2.2f
        score += balance * 0.20f
        score += safety.coerceIn(0f, 0.14f) * 0.55f

        if (p.shouldProtectSkin && removedTop > 0.08f) score -= 0.90f
        if (p.dominant == SceneDominant.SKY_LANDSCAPE && removedTop > 0.14f) score -= 0.55f
        if (removedTop > emptyBandTop + 0.08f && emptyBandTop > 0f) score -= 0.35f
        if (subjectYAfter < 0.28f || subjectYAfter > 0.80f) score -= 0.25f
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
                    profile.shouldEnhanceObjectMaterial -> objectTerm * 0.60f + generic * 0.30f + foreground * 0.10f
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
        val padTop = if (profile.shouldProtectSkin) 0.10f else 0.050f
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
                val empty = dense.luminance[i] * dense.smoothness[i] * (1f - dense.saliency[i] * 0.80f) * (1f - dense.warmObjectLikelihood[i] * 0.90f) * (1f - dense.skinLikelihood[i] * 0.90f)
                if (ny < 0.40f) { top += empty; topCount++ }
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
