package com.aiphotostudio.pipeline

import kotlin.math.max

/**
 * High-level scene understanding built from dense maps, region maps, and global analysis.
 * This is not a hard category label. It is a weighted profile used to filter candidates
 * and guide scoring.
 */
object SceneUnderstandingEngine {
    fun build(a: AnalysisResult): SceneUnderstandingProfile {
        val dense = a.denseMap.summary
        val region = a.regionMap

        val portrait = (a.portraitSafetyLikelihood * 0.45f + dense.skinPresence * 0.30f + region.portraitConfidence * 0.25f)
            .coerceIn(0f, 1f)
        val objectMaterial = (a.ornateObjectLikelihood * 0.38f + dense.warmObjectPresence * 0.30f + region.ornateObjectConfidence * 0.22f + dense.averageTexture * 0.10f)
            .coerceIn(0f, 1f)
        val skyHeavy = max(a.skyLikelihood, max(dense.topSkyPressure, region.skyPressure)).coerceIn(0f, 1f)
        val nature = max(a.natureLikelihood, max(dense.greenPresence, region.greenDominance)).coerceIn(0f, 1f)
        val foregroundSubject = max(a.foregroundHeroLikelihood, max(region.foregroundWeight, dense.centerSaliency)).coerceIn(0f, 1f)
        val backgroundClutter = max(a.backgroundDistraction, max(region.edgeDistraction, dense.edgeDistraction)).coerceIn(0f, 1f)
        val lowLight = ((1f - a.averageLuminance) * 0.55f + a.noiseEstimate * 0.45f).coerceIn(0f, 1f)
        val highKey = (a.averageLuminance * 0.55f + dense.averageLuminance * 0.45f).coerceIn(0f, 1f)
        val backlit = (skyHeavy * 0.45f + (a.topLuminance - a.middleLuminance).coerceIn(0f, 1f) * 0.35f + foregroundSubject * 0.20f).coerceIn(0f, 1f)
        val flatLight = ((1f - a.dynamicRange) * 0.65f + (1f - dense.averageEdge).coerceIn(0f, 1f) * 0.35f).coerceIn(0f, 1f)
        val dramaticContrast = (a.dynamicRange * 0.70f + dense.averageEdge * 0.30f).coerceIn(0f, 1f)
        val emptyTop = max(region.emptyTopPressure, dense.emptyTopPressure).coerceIn(0f, 1f)
        val subjectStrength = max(foregroundSubject, dense.saliencyStrength).coerceIn(0f, 1f)

        val dominant = when {
            portrait > 0.24f && portrait > objectMaterial * 1.15f -> SceneDominant.PERSON
            objectMaterial > 0.18f && objectMaterial >= portrait * 0.85f -> SceneDominant.OBJECT_MATERIAL
            skyHeavy > 0.38f && foregroundSubject < 0.28f -> SceneDominant.SKY_LANDSCAPE
            nature > 0.34f -> SceneDominant.NATURE
            lowLight > 0.58f -> SceneDominant.LOW_LIGHT
            else -> SceneDominant.GENERAL
        }

        return SceneUnderstandingProfile(
            dominant = dominant,
            portraitLikelihood = portrait,
            objectMaterialLikelihood = objectMaterial,
            skyHeavyLikelihood = skyHeavy,
            natureLikelihood = nature,
            foregroundSubjectLikelihood = foregroundSubject,
            backgroundClutter = backgroundClutter,
            lowLightLikelihood = lowLight,
            highKeyLikelihood = highKey,
            backlitLikelihood = backlit,
            flatLightLikelihood = flatLight,
            dramaticContrastLikelihood = dramaticContrast,
            emptyTopPressure = emptyTop,
            subjectStrength = subjectStrength,
            subjectX = dense.subjectX,
            subjectY = dense.subjectY,
            shouldProtectSkin = portrait > 0.22f && objectMaterial < 0.42f,
            shouldEnhanceObjectMaterial = objectMaterial > 0.18f && portrait < 0.38f,
            shouldRecoverSky = skyHeavy > 0.25f,
            shouldCalmBackground = backgroundClutter > 0.22f || nature > 0.30f,
            shouldUseLowLightCandidate = lowLight > 0.55f,
            shouldUseHighKeyCandidate = highKey > 0.68f && a.dynamicRange < 0.56f
        )
    }
}

data class SceneUnderstandingProfile(
    val dominant: SceneDominant,
    val portraitLikelihood: Float,
    val objectMaterialLikelihood: Float,
    val skyHeavyLikelihood: Float,
    val natureLikelihood: Float,
    val foregroundSubjectLikelihood: Float,
    val backgroundClutter: Float,
    val lowLightLikelihood: Float,
    val highKeyLikelihood: Float,
    val backlitLikelihood: Float,
    val flatLightLikelihood: Float,
    val dramaticContrastLikelihood: Float,
    val emptyTopPressure: Float,
    val subjectStrength: Float,
    val subjectX: Float,
    val subjectY: Float,
    val shouldProtectSkin: Boolean,
    val shouldEnhanceObjectMaterial: Boolean,
    val shouldRecoverSky: Boolean,
    val shouldCalmBackground: Boolean,
    val shouldUseLowLightCandidate: Boolean,
    val shouldUseHighKeyCandidate: Boolean
)

enum class SceneDominant {
    PERSON,
    OBJECT_MATERIAL,
    SKY_LANDSCAPE,
    NATURE,
    LOW_LIGHT,
    GENERAL
}
