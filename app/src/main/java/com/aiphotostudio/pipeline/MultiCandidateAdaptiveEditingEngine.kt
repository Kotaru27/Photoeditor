package com.aiphotostudio.pipeline

import android.graphics.Bitmap
import android.graphics.Color
import com.aiphotostudio.editgraph.ColorOperation
import com.aiphotostudio.editgraph.DetailOperation
import com.aiphotostudio.editgraph.EditGraph
import com.aiphotostudio.editgraph.LocalLightOperation
import com.aiphotostudio.editgraph.ToneOperation
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * v1.1.0 first multi-candidate adaptive grading engine.
 *
 * It does not generate pixels. It creates multiple non-destructive EditGraph candidates,
 * renders small previews, scores them, and returns the best graph for the final render.
 */
object MultiCandidateAdaptiveEditingEngine {
    fun chooseBest(source: Bitmap, analysis: AnalysisResult): CandidateSelectionResult {
        val profile = SceneUnderstandingEngine.build(analysis)
        val base = ProfessionalEditingPlanner.plan(analysis)
        val candidates = generateCandidates(base, analysis, profile)
        val scoringBitmap = downscaleForScoring(source)
        val originalStats = CandidateStats.measure(scoringBitmap)

        var best: CandidateResult? = null
        for (candidate in candidates) {
            val rendered = LocalGradingRenderer.render(scoringBitmap, candidate.graph)
            val stats = CandidateStats.measure(rendered)
            val score = scoreCandidate(originalStats, stats, analysis, profile, candidate)
            val result = CandidateResult(candidate, score)
            if (best == null || result.score > best.score) best = result
        }

        val selected = best ?: CandidateResult(Candidate("Balanced professional edit", base), 0)
        val explanation = EditExplanationBuilder.build(profile, selected.candidate.name)
        return CandidateSelectionResult(
            graph = selected.candidate.graph.copy(
                professionalIntent = selected.candidate.name,
                intentReason = explanation.shortSummary
            ),
            candidateName = selected.candidate.name,
            explanation = explanation,
            score = selected.score,
            candidateCount = candidates.size
        )
    }

    private fun generateCandidates(base: EditGraph, a: AnalysisResult, profile: SceneUnderstandingProfile): List<Candidate> {
        val list = mutableListOf<Candidate>()
        list += Candidate("Clean natural grade", base.scaleCreative(0.72f).copy(
            professionalIntent = "Clean natural grade",
            tone = base.tone.copy(
                curveStrength = max(base.tone.curveStrength * 0.70f, 0.05f),
                highlightSoftness = max(base.tone.highlightSoftness, 0.10f)
            ),
            color = base.color.copy(
                vibrance = base.color.vibrance * 0.65f,
                saturation = base.color.saturation * 0.60f,
                colorSeparation = base.color.colorSeparation * 0.55f
            )
        ))

        list += Candidate("Subject depth polish", base.copy(
            professionalIntent = "Subject depth polish",
            tone = base.tone.copy(
                contrast = (base.tone.contrast + 0.04f).coerceIn(-0.22f, 0.28f),
                shadows = (base.tone.shadows + 0.05f).coerceIn(-0.10f, 0.45f),
                highlights = (base.tone.highlights - 0.06f).coerceIn(-0.58f, 0.10f),
                curveStrength = (base.tone.curveStrength + 0.06f).coerceIn(-0.08f, 0.20f),
                midtoneLift = (base.tone.midtoneLift + 0.05f).coerceIn(0f, 0.16f),
                highlightSoftness = (base.tone.highlightSoftness + 0.05f).coerceIn(0f, 0.24f)
            ),
            color = base.color.copy(
                vibrance = (base.color.vibrance + 0.04f).coerceIn(-0.12f, 0.26f),
                colorSeparation = (base.color.colorSeparation + 0.05f).coerceIn(0f, 0.16f)
            ),
            local = base.local.copy(
                subjectLift = (base.local.subjectLift + 0.10f).coerceIn(0f, 0.34f),
                subjectContrast = (base.local.subjectContrast + 0.08f).coerceIn(0f, 0.26f),
                backgroundCalm = (base.local.backgroundCalm + 0.07f).coerceIn(0f, 0.34f),
                centerFocus = (base.local.centerFocus + 0.07f).coerceIn(0f, 0.30f)
            )
        ))

        list += Candidate("Soft human-safe grade", base.scaleCreative(0.62f).copy(
            professionalIntent = "Soft human-safe grade",
            tone = base.tone.copy(
                exposure = (base.tone.exposure - 0.05f).coerceIn(-0.55f, 0.55f),
                contrast = (base.tone.contrast - 0.05f).coerceIn(-0.22f, 0.28f),
                highlights = (base.tone.highlights - 0.14f).coerceIn(-0.58f, 0.10f),
                shadows = (base.tone.shadows + 0.04f).coerceIn(-0.10f, 0.45f),
                curveStrength = min(base.tone.curveStrength, 0.07f),
                highlightSoftness = max(base.tone.highlightSoftness, 0.22f)
            ),
            color = base.color.copy(
                temperature = base.color.temperature * 0.55f,
                vibrance = min(base.color.vibrance, 0.04f),
                saturation = min(base.color.saturation, -0.02f),
                colorSeparation = base.color.colorSeparation * 0.35f
            ),
            detail = base.detail.copy(
                clarity = min(base.detail.clarity, 0.015f),
                texture = min(base.detail.texture, 0.015f),
                sharpness = min(base.detail.sharpness, 0.06f),
                noiseReduction = max(base.detail.noiseReduction, 0.08f)
            ),
            local = base.local.copy(
                subjectLift = min(base.local.subjectLift, 0.12f),
                subjectContrast = min(base.local.subjectContrast, 0.06f),
                heroColorRichness = 0f,
                backgroundCalm = max(base.local.backgroundCalm, 0.16f),
                edgeVignette = min(base.local.edgeVignette, 0.08f)
            )
        ))

        list += Candidate("Rich object detail grade", base.copy(
            professionalIntent = "Rich object detail grade",
            tone = base.tone.copy(
                contrast = (base.tone.contrast + 0.06f).coerceIn(-0.22f, 0.28f),
                highlights = (base.tone.highlights - 0.08f).coerceIn(-0.58f, 0.10f),
                shadows = (base.tone.shadows + 0.08f).coerceIn(-0.10f, 0.45f),
                curveStrength = (base.tone.curveStrength + 0.08f).coerceIn(-0.08f, 0.20f),
                midtoneLift = (base.tone.midtoneLift + 0.06f).coerceIn(0f, 0.16f)
            ),
            color = base.color.copy(
                vibrance = (base.color.vibrance + 0.06f).coerceIn(-0.12f, 0.26f),
                saturation = base.color.saturation.coerceIn(-0.06f, 0.03f),
                colorSeparation = (base.color.colorSeparation + 0.07f).coerceIn(0f, 0.16f)
            ),
            detail = base.detail.copy(
                clarity = (base.detail.clarity + 0.04f).coerceIn(0f, 0.10f),
                texture = (base.detail.texture + 0.035f).coerceIn(0f, 0.08f),
                sharpness = (base.detail.sharpness + 0.035f).coerceIn(0f, 0.16f)
            ),
            local = base.local.copy(
                subjectLift = (base.local.subjectLift + 0.14f).coerceIn(0f, 0.34f),
                subjectContrast = (base.local.subjectContrast + 0.12f).coerceIn(0f, 0.26f),
                backgroundCalm = (base.local.backgroundCalm + 0.12f).coerceIn(0f, 0.34f),
                heroColorRichness = (base.local.heroColorRichness + 0.16f).coerceIn(0f, 0.24f),
                centerFocus = (base.local.centerFocus + 0.11f).coerceIn(0f, 0.30f)
            )
        ))

        list += Candidate("Atmospheric sky and depth grade", base.copy(
            professionalIntent = "Atmospheric sky and depth grade",
            tone = base.tone.copy(
                highlights = (base.tone.highlights - 0.18f).coerceIn(-0.58f, 0.10f),
                whites = (base.tone.whites - 0.08f).coerceIn(-0.18f, 0.12f),
                shadows = (base.tone.shadows + 0.06f).coerceIn(-0.10f, 0.45f),
                highlightSoftness = (base.tone.highlightSoftness + 0.10f).coerceIn(0f, 0.24f),
                curveStrength = (base.tone.curveStrength + 0.04f).coerceIn(-0.08f, 0.20f)
            ),
            color = base.color.copy(
                vibrance = (base.color.vibrance + 0.025f).coerceIn(-0.12f, 0.26f),
                saturation = (base.color.saturation - 0.015f).coerceIn(-0.12f, 0.08f)
            ),
            local = base.local.copy(
                skyRecovery = (base.local.skyRecovery + 0.12f).coerceIn(0f, 0.24f),
                foregroundDepth = (base.local.foregroundDepth + 0.08f).coerceIn(0f, 0.22f),
                backgroundCalm = (base.local.backgroundCalm + 0.04f).coerceIn(0f, 0.34f),
                edgeVignette = min(base.local.edgeVignette, 0.12f)
            )
        ))

        list += Candidate("Muted background color harmony", base.copy(
            professionalIntent = "Muted background color harmony",
            color = base.color.copy(
                vibrance = (base.color.vibrance + 0.02f).coerceIn(-0.12f, 0.26f),
                saturation = (base.color.saturation - 0.035f).coerceIn(-0.12f, 0.08f),
                colorSeparation = (base.color.colorSeparation + 0.06f).coerceIn(0f, 0.16f)
            ),
            tone = base.tone.copy(
                curveStrength = (base.tone.curveStrength + 0.04f).coerceIn(-0.08f, 0.20f),
                highlightSoftness = (base.tone.highlightSoftness + 0.04f).coerceIn(0f, 0.24f)
            ),
            local = base.local.copy(
                backgroundCalm = (base.local.backgroundCalm + 0.14f).coerceIn(0f, 0.34f),
                subjectLift = (base.local.subjectLift + 0.05f).coerceIn(0f, 0.34f),
                centerFocus = (base.local.centerFocus + 0.06f).coerceIn(0f, 0.30f)
            )
        ))

        if (a.averageLuminance < 0.38f) {
            list += Candidate("Low-light natural rescue", base.copy(
                professionalIntent = "Low-light natural rescue",
                tone = base.tone.copy(
                    exposure = (base.tone.exposure + 0.12f).coerceIn(-0.55f, 0.55f),
                    shadows = (base.tone.shadows + 0.14f).coerceIn(-0.10f, 0.45f),
                    highlights = (base.tone.highlights - 0.06f).coerceIn(-0.58f, 0.10f),
                    contrast = (base.tone.contrast + 0.02f).coerceIn(-0.22f, 0.28f),
                    highlightSoftness = max(base.tone.highlightSoftness, 0.12f)
                ),
                detail = base.detail.copy(
                    noiseReduction = (base.detail.noiseReduction + 0.12f).coerceIn(0f, 0.30f),
                    sharpness = min(base.detail.sharpness, 0.08f),
                    clarity = min(base.detail.clarity, 0.05f)
                ),
                local = base.local.copy(
                    subjectLift = (base.local.subjectLift + 0.08f).coerceIn(0f, 0.34f),
                    edgeVignette = min(base.local.edgeVignette, 0.10f)
                )
            ))
        }

        return filterCandidates(list, profile)
    }

    private fun filterCandidates(candidates: List<Candidate>, profile: SceneUnderstandingProfile): List<Candidate> {
        val filtered = candidates.filter { candidate ->
            val name = candidate.name.lowercase()
            when {
                name.contains("human") -> profile.shouldProtectSkin || profile.dominant == SceneDominant.PERSON
                name.contains("object") -> profile.shouldEnhanceObjectMaterial || profile.dominant == SceneDominant.OBJECT_MATERIAL
                name.contains("sky") || name.contains("atmospheric") -> profile.shouldRecoverSky || profile.dominant == SceneDominant.SKY_LANDSCAPE
                name.contains("low-light") -> profile.shouldUseLowLightCandidate || profile.dominant == SceneDominant.LOW_LIGHT
                else -> true
            }
        }.toMutableList()

        if (filtered.none { it.name.contains("Clean", ignoreCase = true) }) {
            candidates.firstOrNull { it.name.contains("Clean", ignoreCase = true) }?.let { filtered.add(0, it) }
        }
        if (profile.shouldEnhanceObjectMaterial && filtered.none { it.name.contains("object", ignoreCase = true) }) {
            candidates.firstOrNull { it.name.contains("object", ignoreCase = true) }?.let { filtered.add(1.coerceAtMost(filtered.size), it) }
        }
        if (filtered.none { it.name.contains("Subject", ignoreCase = true) } && profile.subjectStrength > 0.22f && !profile.shouldEnhanceObjectMaterial) {
            candidates.firstOrNull { it.name.contains("Subject", ignoreCase = true) }?.let { filtered.add(it) }
        }
        // v1.4.3 performance optimization: candidate filtering now keeps up to 4 candidates instead of 5.
        return filtered.distinctBy { it.name }.take(4).ifEmpty { candidates.take(3) }
    }

    private fun scoreCandidate(original: CandidateStats, edited: CandidateStats, a: AnalysisResult, profile: SceneUnderstandingProfile, candidate: Candidate): Int {
        var score = 100

        val changeStrength = abs(edited.averageLum - original.averageLum) + abs(edited.saturation - original.saturation) + abs(edited.localContrast - original.localContrast)
        if (changeStrength < 0.035f) score -= 38
        if (changeStrength > 0.42f) score -= 14

        if (edited.highlightClip > max(0.055f, original.highlightClip + 0.035f)) score -= 30
        if (edited.shadowClip > max(0.075f, original.shadowClip + 0.045f)) score -= 24
        if (edited.skinHot > max(0.09f, original.skinHot + 0.04f)) score -= 36
        if (edited.topHot > max(0.12f, original.topHot + 0.06f)) score -= 18
        if (edited.saturation > max(0.56f, original.saturation + 0.24f)) score -= 22
        if (edited.edgeDarkness > original.edgeDarkness + 0.18f) score -= 12

        val subjectGain = edited.centerSeparation - original.centerSeparation
        val backgroundGain = original.edgeSaturation - edited.edgeSaturation
        if (abs(edited.saturation - original.saturation) > 0.05f && subjectGain < 0.012f && backgroundGain < 0.012f) {
            score -= 24
        }
        if (subjectGain < 0.006f && backgroundGain < 0.006f && changeStrength < 0.08f) {
            score -= 20
        }
        score += (subjectGain * 150f).toInt()
        score += (backgroundGain * 95f).toInt()

        if (profile.shouldProtectSkin) {
            if (candidate.name.contains("human", ignoreCase = true) || candidate.name.contains("portrait", ignoreCase = true)) score += 34
            if (candidate.name.contains("object", ignoreCase = true)) score -= 34
            if (edited.skinHot > original.skinHot + 0.02f) score -= 28
        } else if (candidate.name.contains("human", ignoreCase = true)) {
            score -= 22
        }

        if (profile.shouldEnhanceObjectMaterial) {
            if (candidate.name.contains("object", ignoreCase = true)) score += 48
            if (candidate.name.contains("Subject", ignoreCase = true)) score += 12
            if (candidate.name.contains("human", ignoreCase = true) || candidate.name.contains("portrait", ignoreCase = true)) score -= 48
        }

        if (profile.shouldRecoverSky) {
            if (candidate.name.contains("sky", ignoreCase = true) || candidate.name.contains("Atmospheric", ignoreCase = true)) score += 16
            if (edited.topHot < original.topHot) score += 10
        }

        if (profile.shouldCalmBackground && edited.edgeSaturation < original.edgeSaturation) score += 18
        if (profile.subjectStrength > 0.30f && subjectGain > 0.008f) score += 16
        if (profile.objectMaterialLikelihood > 0.12f && candidate.name.contains("object", ignoreCase = true)) score += 28
        if (profile.objectMaterialLikelihood > 0.18f && candidate.name.contains("Subject", ignoreCase = true)) score -= 8
        if (profile.portraitLikelihood > 0.26f && candidate.name.contains("human", ignoreCase = true)) score += 16
        if (profile.lowLightLikelihood > 0.55f && candidate.name.contains("Low-light", ignoreCase = true)) score += 10

        return score.coerceIn(0, 160)
    }

    private fun downscaleForScoring(source: Bitmap): Bitmap {
        // v1.4.3 performance optimization: reduced candidate scoring preview from 900px to 720px.
        val maxDim = 720
        val largest = max(source.width, source.height)
        if (largest <= maxDim) return source
        val scale = maxDim.toFloat() / largest.toFloat()
        val w = (source.width * scale).toInt().coerceAtLeast(1)
        val h = (source.height * scale).toInt().coerceAtLeast(1)
        return Bitmap.createScaledBitmap(source, w, h, true)
    }
}

data class CandidateSelectionResult(
    val graph: EditGraph,
    val candidateName: String,
    val explanation: EditDecisionSummary,
    val score: Int,
    val candidateCount: Int
)

private data class Candidate(
    val name: String,
    val graph: EditGraph
)

private data class CandidateResult(
    val candidate: Candidate,
    val score: Int
)

private data class CandidateStats(
    val averageLum: Float,
    val saturation: Float,
    val localContrast: Float,
    val highlightClip: Float,
    val shadowClip: Float,
    val topHot: Float,
    val skinHot: Float,
    val edgeDarkness: Float,
    val edgeSaturation: Float,
    val centerSeparation: Float
) {
    companion object {
        fun measure(bitmap: Bitmap): CandidateStats {
            val width = bitmap.width
            val height = bitmap.height
            val pixels = IntArray(width * height)
            bitmap.getPixels(pixels, 0, width, 0, 0, width, height)
            val step = max(1, max(width, height) / 480)

            var count = 0
            var lumSum = 0.0
            var satSum = 0.0
            var contrast = 0.0
            var prev = -1f
            var hi = 0
            var lo = 0
            var topHot = 0
            var topCount = 0
            var skinHot = 0
            var skinCount = 0
            var edgeDark = 0.0
            var edgeSat = 0.0
            var edgeCount = 0
            var centerLum = 0.0
            var centerCount = 0
            var borderLum = 0.0
            var borderCount = 0

            var y = 0
            while (y < height) {
                var x = 0
                while (x < width) {
                    val c = pixels[y * width + x]
                    val r = Color.red(c) / 255f
                    val g = Color.green(c) / 255f
                    val b = Color.blue(c) / 255f
                    val lum = 0.2126f * r + 0.7152f * g + 0.0722f * b
                    val mx = max(r, max(g, b))
                    val mn = min(r, min(g, b))
                    val sat = mx - mn
                    lumSum += lum
                    satSum += sat
                    if (lum > 0.985f) hi++
                    if (lum < 0.018f) lo++
                    if (prev >= 0f) contrast += abs(lum - prev)
                    prev = lum
                    if (y < height / 3) {
                        topCount++
                        if (lum > 0.86f) topHot++
                    }
                    val skinLike = r > g && g > b && r - b > 0.07f && lum > 0.28f
                    if (skinLike) {
                        skinCount++
                        if (lum > 0.72f) skinHot++
                    }
                    val nx = x.toFloat() / width
                    val ny = y.toFloat() / height
                    val edge = max(abs(nx - 0.5f), abs(ny - 0.5f)) > 0.42f
                    if (edge) {
                        edgeCount++
                        edgeDark += 1.0 - lum
                        edgeSat += sat
                    }
                    val center = abs(nx - 0.5f) < 0.28f && abs(ny - 0.55f) < 0.34f
                    if (center) {
                        centerCount++
                        centerLum += lum
                    } else if (edge) {
                        borderCount++
                        borderLum += lum
                    }
                    count++
                    x += step
                }
                y += step
            }

            val safe = max(1, count)
            val cLum = (centerLum / max(1, centerCount)).toFloat()
            val bLum = (borderLum / max(1, borderCount)).toFloat()
            return CandidateStats(
                averageLum = (lumSum / safe).toFloat(),
                saturation = (satSum / safe).toFloat(),
                localContrast = (contrast / safe).toFloat(),
                highlightClip = hi.toFloat() / safe,
                shadowClip = lo.toFloat() / safe,
                topHot = topHot.toFloat() / max(1, topCount),
                skinHot = skinHot.toFloat() / max(1, skinCount),
                edgeDarkness = (edgeDark / max(1, edgeCount)).toFloat(),
                edgeSaturation = (edgeSat / max(1, edgeCount)).toFloat(),
                centerSeparation = abs(cLum - bLum)
            )
        }
    }
}

private fun EditGraph.scaleCreative(keep: Float): EditGraph {
    val k = keep.coerceIn(0f, 1f)
    return copy(
        tone = ToneOperation(
            exposure = tone.exposure * k,
            contrast = tone.contrast * k,
            highlights = tone.highlights * k,
            shadows = tone.shadows * k,
            whites = tone.whites * k,
            blacks = tone.blacks * k,
            gamma = 1f + (tone.gamma - 1f) * k,
            curveStrength = tone.curveStrength * k,
            midtoneLift = tone.midtoneLift * k,
            highlightSoftness = tone.highlightSoftness
        ),
        color = ColorOperation(
            temperature = color.temperature * k,
            tint = color.tint * k,
            vibrance = color.vibrance * k,
            saturation = color.saturation * k,
            colorSeparation = color.colorSeparation * k,
            memoryColorProtect = color.memoryColorProtect
        ),
        detail = DetailOperation(
            clarity = detail.clarity * k,
            texture = detail.texture * k,
            sharpness = detail.sharpness * k,
            noiseReduction = detail.noiseReduction,
            dehaze = detail.dehaze * k,
            haloGuard = detail.haloGuard
        ),
        local = LocalLightOperation(
            subjectLift = local.subjectLift * k,
            subjectContrast = local.subjectContrast * k,
            backgroundCalm = local.backgroundCalm * k,
            skyRecovery = local.skyRecovery,
            foregroundDepth = local.foregroundDepth * k,
            edgeVignette = local.edgeVignette * k,
            centerFocus = local.centerFocus * k,
            heroColorRichness = local.heroColorRichness * k
        )
    )
}
