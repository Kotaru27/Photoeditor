package com.aiphotostudio.pipeline

import com.aiphotostudio.editgraph.GeometryOperation
import kotlin.math.abs

/**
 * Auto Frame 2.0: candidate-based composition scoring.
 *
 * Generates several safe crop candidates and chooses the one that improves composition
 * based on subject safety, empty-space removal, sky reduction, and distraction reduction.
 */
object AutoFrameEngine {
    fun plan(analysis: AnalysisResult, profile: SceneUnderstandingProfile): GeometryOperation {
        val dense = analysis.denseMap
        val bounds = estimateSubjectBounds(dense)
        val emptyTop = maxOf(analysis.regionMap.emptyTopPressure, analysis.denseMap.summary.emptyTopPressure)
        val skyPressure = maxOf(analysis.regionMap.skyPressure, analysis.denseMap.summary.topSkyPressure)
        val objectScene = profile.shouldEnhanceObjectMaterial || profile.dominant == SceneDominant.OBJECT_MATERIAL
        val portraitSafe = profile.shouldProtectSkin || profile.dominant == SceneDominant.PERSON

        val candidates = mutableListOf<CropCandidate>()
        candidates += CropCandidate(0f, 0f, 1f, 1f)

        val topOptions = if (portraitSafe) {
            listOf(0.03f, 0.05f, 0.07f)
        } else if (objectScene && emptyTop > 0.18f) {
            listOf(0.06f, 0.10f, 0.14f, 0.18f, 0.22f)
        } else if (skyPressure > 0.28f || emptyTop > 0.22f) {
            listOf(0.05f, 0.08f, 0.12f, 0.15f)
        } else {
            listOf(0.04f, 0.07f)
        }

        for (top in topOptions) {
            candidates += CropCandidate(0f, top, 1f, 1f)
            if (!portraitSafe && profile.backgroundClutter > 0.24f) {
                candidates += CropCandidate(0.015f, top, 0.985f, 1f)
                candidates += CropCandidate(0.025f, top, 0.975f, 1f)
            }
        }

        val best = candidates.maxByOrNull { scoreCandidate(it, bounds, analysis, profile) } ?: candidates.first()
        val originalScore = scoreCandidate(candidates.first(), bounds, analysis, profile)
        val bestScore = scoreCandidate(best, bounds, analysis, profile)

        // Only crop when the improvement is real enough.
        val chosen = if (bestScore > originalScore + 0.06f) best else candidates.first()
        return GeometryOperation(
            cropLeft = chosen.left.coerceIn(0f, 0.18f),
            cropTop = chosen.top.coerceIn(0f, if (portraitSafe) 0.09f else 0.25f),
            cropRight = chosen.right.coerceIn(0.82f, 1f),
            cropBottom = chosen.bottom.coerceIn(0.82f, 1f)
        )
    }

    private fun scoreCandidate(c: CropCandidate, b: SubjectBounds, a: AnalysisResult, p: SceneUnderstandingProfile): Float {
        val cropW = c.right - c.left
        val cropH = c.bottom - c.top
        if (cropW <= 0.62f || cropH <= 0.62f) return -10f

        val subjectInside = c.left < b.left && c.right > b.right && c.top < b.top && c.bottom > b.bottom
        if (!subjectInside) return -8f

        val topMargin = b.top - c.top
        val bottomMargin = c.bottom - b.bottom
        val leftMargin = b.left - c.left
        val rightMargin = c.right - b.right
        val safety = listOf(topMargin, bottomMargin, leftMargin, rightMargin).minOrNull() ?: 0f
        if (safety < 0.025f) return -4f

        val subjectAreaBefore = (b.right - b.left) * (b.bottom - b.top)
        val subjectAreaAfter = subjectAreaBefore / (cropW * cropH)
        val sizeGain = (subjectAreaAfter - subjectAreaBefore).coerceIn(0f, 0.35f)
        val emptyTop = maxOf(a.regionMap.emptyTopPressure, a.denseMap.summary.emptyTopPressure)
        val skyPressure = maxOf(a.regionMap.skyPressure, a.denseMap.summary.topSkyPressure)
        val removedTop = c.top
        val subjectYAfter = ((b.top + b.bottom) / 2f - c.top) / cropH
        val thirdsScore = 1f - minOf(abs(subjectYAfter - 0.58f), abs(subjectYAfter - 0.42f), abs(subjectYAfter - 0.50f))
        val clutterReduction = (c.left + (1f - c.right)) * p.backgroundClutter

        var score = 0f
        score += sizeGain * 2.6f
        score += removedTop * emptyTop * 3.2f
        score += removedTop * skyPressure * 2.2f
        score += thirdsScore * 0.18f
        score += clutterReduction * 1.6f
        score += safety.coerceIn(0f, 0.12f) * 0.75f

        if (p.shouldProtectSkin && c.top > 0.09f) score -= 0.75f
        if (p.dominant == SceneDominant.SKY_LANDSCAPE && c.top > 0.12f) score -= 0.45f
        if (removedTop > 0.18f && emptyTop < 0.24f) score -= 0.55f
        return score
    }

    private fun estimateSubjectBounds(dense: DenseAnalysisMap): SubjectBounds {
        var maxS = 0f
        for (v in dense.saliency) if (v > maxS) maxS = v
        val threshold = (maxS * 0.58f).coerceAtLeast(0.22f)
        var minX = 1f; var minY = 1f; var maxX = 0f; var maxY = 0f
        var found = false
        for (y in 0 until dense.height) {
            for (x in 0 until dense.width) {
                val i = y * dense.width + x
                val subject = dense.saliency[i] * (1f - dense.skyLikelihood[i] * 0.85f) * (1f - dense.distraction[i] * 0.25f)
                if (subject >= threshold) {
                    val nx = x.toFloat() / (dense.width - 1).coerceAtLeast(1)
                    val ny = y.toFloat() / (dense.height - 1).coerceAtLeast(1)
                    minX = minOf(minX, nx); maxX = maxOf(maxX, nx)
                    minY = minOf(minY, ny); maxY = maxOf(maxY, ny)
                    found = true
                }
            }
        }
        if (!found) {
            val cx = dense.summary.subjectX
            val cy = dense.summary.subjectY
            return SubjectBounds((cx - 0.22f).coerceIn(0f, 1f), (cy - 0.24f).coerceIn(0f, 1f), (cx + 0.22f).coerceIn(0f, 1f), (cy + 0.24f).coerceIn(0f, 1f))
        }
        // Expand bounds to keep safe margins around the subject.
        return SubjectBounds(
            (minX - 0.05f).coerceIn(0f, 1f),
            (minY - 0.06f).coerceIn(0f, 1f),
            (maxX + 0.05f).coerceIn(0f, 1f),
            (maxY + 0.06f).coerceIn(0f, 1f)
        )
    }
}

private data class CropCandidate(val left: Float, val top: Float, val right: Float, val bottom: Float)
private data class SubjectBounds(val left: Float, val top: Float, val right: Float, val bottom: Float)
