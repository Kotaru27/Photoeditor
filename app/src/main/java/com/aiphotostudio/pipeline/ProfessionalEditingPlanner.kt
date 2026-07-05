package com.aiphotostudio.pipeline

import com.aiphotostudio.editgraph.ColorOperation
import com.aiphotostudio.editgraph.DetailOperation
import com.aiphotostudio.editgraph.EditGraph
import com.aiphotostudio.editgraph.GeometryOperation
import com.aiphotostudio.editgraph.LocalLightOperation
import com.aiphotostudio.editgraph.ToneOperation
import kotlin.math.abs

/**
 * Converts measurements into a restrained, image-specific professional treatment.
 * This is not a filter picker. It chooses a photographic recipe from the image's
 * measured light, range, subject hints, color behavior, and texture.
 */
object ProfessionalEditingPlanner {
    fun plan(result: AnalysisResult): EditGraph {
        val recipe = chooseRecipe(result)

        val exposure = when {
            result.averageLuminance < 0.30f && result.highlightClipping < 0.02f -> 0.38f
            result.averageLuminance < 0.38f -> 0.24f
            result.averageLuminance < 0.45f && result.midtonePosition < 0.42f -> 0.12f
            result.averageLuminance > 0.74f && result.highlightClipping < 0.015f -> -0.20f
            result.averageLuminance > 0.64f -> -0.08f
            else -> 0f
        } + recipe.exposureBias

        val highlights = when {
            result.highlightClipping > 0.045f -> -0.48f
            result.highlightClipping > 0.018f -> -0.32f
            result.skyLikelihood > 0.34f && result.topLuminance > result.middleLuminance + 0.10f -> -0.24f
            result.averageLuminance > 0.64f -> -0.12f
            else -> -0.04f
        } + recipe.highlightBias

        val shadows = when {
            result.shadowClipping > 0.06f && result.peopleLikelihood > 0.12f -> 0.38f
            result.shadowClipping > 0.05f -> 0.28f
            result.averageLuminance < 0.40f -> 0.20f
            result.dynamicRange < 0.38f -> 0.12f
            else -> 0.04f
        } + recipe.shadowBias

        val contrast = when {
            result.dynamicRange < 0.30f -> 0.20f
            result.dynamicRange < 0.48f -> 0.12f
            result.dynamicRange > 0.84f && result.peopleLikelihood > 0.10f -> -0.06f
            result.dynamicRange > 0.84f -> -0.02f
            else -> 0.07f
        } + recipe.contrastBias

        val whites = when {
            result.highlightClipping > 0.02f -> -0.12f
            result.skyLikelihood > 0.35f -> 0.00f
            else -> 0.05f
        }
        val blacks = when {
            result.shadowClipping > 0.035f -> 0.06f
            recipe.name.contains("Dramatic") -> -0.08f
            else -> -0.04f
        }

        // Correct actual casts gently. Then add only tiny recipe bias when it supports the photo.
        val temperature = (-result.warmthCast * 0.24f + recipe.temperatureBias).coerceIn(-0.22f, 0.22f)
        val tint = (-result.tintCast * 0.22f + recipe.tintBias).coerceIn(-0.16f, 0.16f)

        val vibrance = when {
            result.peopleLikelihood > 0.15f && result.saturationEstimate > 0.30f -> 0.04f
            result.saturationEstimate < 0.10f -> 0.20f
            result.saturationEstimate < 0.24f -> 0.12f
            result.saturationEstimate > 0.44f -> -0.06f
            else -> 0.07f
        } + recipe.vibranceBias
        val saturation = if (result.saturationEstimate > 0.46f) -0.06f else recipe.saturationBias

        val sharpness = when {
            result.noiseEstimate > 0.55f -> 0.03f
            result.peopleLikelihood > 0.18f -> 0.06f
            result.sharpnessEstimate < 0.28f -> 0.14f
            else -> 0.08f
        }
        val noiseReduction = when {
            result.noiseEstimate > 0.55f -> 0.26f
            result.noiseEstimate > 0.35f -> 0.15f
            result.peopleLikelihood > 0.18f -> 0.08f
            else -> 0.04f
        }
        val clarity = when {
            result.peopleLikelihood > 0.18f -> 0.015f
            result.natureLikelihood > 0.20f -> 0.07f
            result.skyLikelihood > 0.35f -> 0.045f
            else -> 0.045f
        } + recipe.clarityBias
        val texture = when {
            result.peopleLikelihood > 0.18f -> 0.015f
            result.noiseEstimate < 0.45f -> 0.06f
            else -> 0.01f
        }
        val dehaze = when {
            result.skyLikelihood > 0.35f && result.dynamicRange < 0.55f -> 0.09f
            result.dynamicRange < 0.32f -> 0.07f
            else -> 0.02f
        } + recipe.dehazeBias

        val autoFrameTop = when {
            result.portraitSafetyLikelihood > 0.24f && result.ornateObjectLikelihood < 0.42f -> 0f
            result.foregroundHeroLikelihood > 0.12f && maxOf(result.regionMap.emptyTopPressure, result.denseMap.summary.emptyTopPressure) > 0.30f -> 0.060f
            result.skyLikelihood > 0.45f && maxOf(result.regionMap.emptyTopPressure, result.denseMap.summary.emptyTopPressure) > 0.34f -> 0.045f
            else -> 0f
        }
        val autoFrameSide = if (result.portraitSafetyLikelihood < 0.24f && result.foregroundHeroLikelihood > 0.22f && result.backgroundDistraction > 0.38f) 0.014f else 0f

        // Universal adaptive goals: recipes are only hints; these cross-image goals shape every photo.
        val dense = result.denseMap.summary
        val subjectPriority = maxOf(result.foregroundHeroLikelihood, result.subjectFocusLikelihood, result.peopleLikelihood, result.regionMap.centerSaliency, dense.centerSaliency).coerceIn(0f, 1f)
        val backgroundPressure = (result.backgroundDistraction * 0.32f + result.regionMap.edgeDistraction * 0.25f + dense.edgeDistraction * 0.25f + dense.distractionStrength * 0.10f + result.natureLikelihood * 0.08f).coerceIn(0f, 1f)
        val skyPressure = maxOf(result.skyLikelihood * 0.65f + (result.topLuminance - result.middleLuminance).coerceIn(0f, 1f) * 0.45f, result.regionMap.skyPressure, dense.topSkyPressure).coerceIn(0f, 1f)
        val textureOpportunity = maxOf(result.centerDetailStrength * (1f - result.noiseEstimate * 0.65f), result.regionMap.textureOpportunity, dense.averageTexture * (1f - result.noiseEstimate * 0.55f)).coerceIn(0f, 1f)
        val skinSafety = if (result.portraitSafetyLikelihood > 0.24f && result.ornateObjectLikelihood < 0.42f) 1f else 0f

        return EditGraph(
            professionalIntent = recipe.name,
            intentReason = recipe.reason,
            tone = ToneOperation(
                exposure = exposure.coerceIn(-0.55f, 0.55f),
                contrast = contrast.coerceIn(-0.22f, 0.28f),
                highlights = highlights.coerceIn(-0.58f, 0.10f),
                shadows = shadows.coerceIn(-0.10f, 0.45f),
                whites = whites.coerceIn(-0.18f, 0.12f),
                blacks = blacks.coerceIn(-0.14f, 0.10f),
                gamma = recipe.gamma.coerceIn(0.86f, 1.12f),
                curveStrength = recipe.curveStrength.coerceIn(-0.08f, 0.20f),
                midtoneLift = (recipe.midtoneLift + subjectPriority * 0.025f).coerceIn(0f, 0.16f),
                highlightSoftness = (recipe.highlightSoftness + skyPressure * 0.035f + skinSafety * 0.025f).coerceIn(0f, 0.24f)
            ),
            color = ColorOperation(
                temperature = temperature,
                tint = tint,
                vibrance = vibrance.coerceIn(-0.12f, 0.26f),
                saturation = saturation.coerceIn(-0.12f, 0.08f),
                colorSeparation = recipe.colorSeparation.coerceIn(0f, 0.16f),
                memoryColorProtect = if (result.portraitSafetyLikelihood > 0.24f || result.skyLikelihood > 0.25f || result.natureLikelihood > 0.18f || result.foregroundHeroLikelihood > 0.18f) 1f else 0.5f
            ),
            detail = DetailOperation(
                clarity = (clarity + textureOpportunity * 0.018f - skinSafety * 0.025f).coerceIn(0f, 0.10f),
                texture = (texture + textureOpportunity * 0.012f - skinSafety * 0.010f).coerceIn(0f, 0.08f),
                sharpness = sharpness.coerceIn(0f, 0.16f),
                noiseReduction = noiseReduction.coerceIn(0f, 0.30f),
                dehaze = dehaze.coerceIn(0f, 0.12f),
                haloGuard = 1f
            ),
            local = LocalLightOperation(
                subjectLift = (recipe.subjectLift + subjectPriority * 0.045f - skinSafety * 0.050f).coerceIn(0f, 0.34f),
                subjectContrast = (recipe.subjectContrast + textureOpportunity * 0.030f - skinSafety * 0.040f).coerceIn(0f, 0.26f),
                backgroundCalm = (recipe.backgroundCalm + backgroundPressure * 0.090f + if (result.backgroundDistraction > 0.42f) 0.04f else 0f).coerceIn(0f, 0.34f),
                skyRecovery = (recipe.skyRecovery + skyPressure * 0.090f + if (result.skyLikelihood > 0.28f) 0.04f else 0f).coerceIn(0f, 0.24f),
                foregroundDepth = recipe.foregroundDepth.coerceIn(0f, 0.22f),
                edgeVignette = recipe.edgeVignette.coerceIn(0f, 0.24f),
                centerFocus = (recipe.centerFocus + subjectPriority * 0.050f).coerceIn(0f, 0.30f),
                heroColorRichness = (recipe.heroColorRichness - skinSafety * 0.20f).coerceIn(0f, 0.24f)
            ),
            geometry = GeometryOperation(
                cropLeft = autoFrameSide,
                cropTop = autoFrameTop,
                cropRight = 1f - autoFrameSide,
                cropBottom = 1f
            )
        )
    }

    private fun chooseRecipe(r: AnalysisResult): Recipe {
        return when {
            r.portraitSafetyLikelihood > 0.24f && r.ornateObjectLikelihood < 0.42f -> Recipe(
                name = "Natural portrait polish",
                reason = "Detected a person-like foreground subject; protect skin, soften harsh highlights, calm background, and keep the face natural.",
                exposureBias = -0.04f,
                contrastBias = -0.03f,
                highlightBias = -0.22f,
                shadowBias = 0.08f,
                vibranceBias = -0.03f,
                saturationBias = -0.03f,
                clarityBias = -0.025f,
                curveStrength = 0.05f,
                midtoneLift = 0.04f,
                highlightSoftness = 0.20f,
                colorSeparation = 0.03f,
                gamma = 1.00f,
                subjectLift = 0.12f,
                subjectContrast = 0.06f,
                backgroundCalm = 0.18f,
                skyRecovery = 0.10f,
                foregroundDepth = 0.04f,
                edgeVignette = 0.08f,
                centerFocus = 0.12f,
                heroColorRichness = 0.00f
            )
            r.ornateObjectLikelihood > 0.20f || r.foregroundHeroLikelihood > 0.12f || (r.centerWarmStrength > 0.20f && r.bottomLuminance < r.topLuminance) -> Recipe(
                name = "Hero object depth polish",
                reason = "Detected a detailed foreground hero subject; make it feel important, calm the background, recover highlights, and add natural depth.",
                contrastBias = 0.06f,
                highlightBias = -0.18f,
                shadowBias = 0.14f,
                vibranceBias = 0.07f,
                saturationBias = -0.01f,
                clarityBias = 0.045f,
                dehazeBias = 0.03f,
                curveStrength = 0.18f,
                midtoneLift = 0.12f,
                highlightSoftness = 0.18f,
                colorSeparation = 0.12f,
                subjectLift = 0.34f,
                subjectContrast = 0.26f,
                backgroundCalm = 0.32f,
                skyRecovery = 0.22f,
                foregroundDepth = 0.18f,
                edgeVignette = 0.18f,
                centerFocus = 0.28f,
                heroColorRichness = 0.22f
            )
            r.subjectFocusLikelihood > 0.28f && r.centerDetailStrength > 0.18f && r.portraitSafetyLikelihood < 0.30f -> Recipe(
                name = "Subject depth and attention sculpt",
                reason = "Detected a strong central subject; lift the subject, calm the background, recover bright areas, and add depth without changing the real image.",
                contrastBias = 0.03f,
                highlightBias = -0.12f,
                shadowBias = 0.10f,
                vibranceBias = 0.03f,
                saturationBias = -0.01f,
                clarityBias = 0.018f,
                curveStrength = 0.13f,
                midtoneLift = 0.09f,
                highlightSoftness = 0.14f,
                colorSeparation = 0.07f,
                subjectLift = 0.28f,
                subjectContrast = 0.22f,
                backgroundCalm = 0.24f,
                edgeVignette = 0.16f,
                centerFocus = 0.26f,
                heroColorRichness = 0.18f
            )
            r.peopleLikelihood > 0.22f && r.subjectFocusLikelihood > 0.30f && r.dynamicRange > 0.62f -> Recipe(
                name = "Subject-focused editorial polish",
                reason = "Detected an important foreground subject with skin-like/warm tones; shape attention, protect natural tones, and soften harsh highlights.",
                contrastBias = -0.02f,
                highlightBias = -0.08f,
                shadowBias = 0.08f,
                vibranceBias = -0.01f,
                saturationBias = -0.01f,
                clarityBias = -0.015f,
                midtoneLift = 0.08f,
                highlightSoftness = 0.14f,
                curveStrength = 0.06f,
                gamma = 1.02f,
                subjectLift = 0.24f,
                subjectContrast = 0.14f,
                backgroundCalm = 0.18f,
                edgeVignette = 0.10f,
                centerFocus = 0.18f,
                heroColorRichness = 0.08f
            )
            r.skyLikelihood > 0.36f && r.bottomLuminance < r.topLuminance - 0.12f -> Recipe(
                name = "Landscape depth and sky recovery",
                reason = "Bright upper frame and darker foreground; recover sky, lift foreground, add depth without HDR halos.",
                contrastBias = 0.03f,
                highlightBias = -0.14f,
                shadowBias = 0.10f,
                vibranceBias = 0.05f,
                saturationBias = 0.00f,
                clarityBias = 0.02f,
                dehazeBias = 0.04f,
                curveStrength = 0.12f,
                colorSeparation = 0.08f,
                highlightSoftness = 0.10f,
                skyRecovery = 0.20f,
                foregroundDepth = 0.14f,
                backgroundCalm = 0.12f,
                edgeVignette = 0.08f
            )
            r.averageLuminance < 0.36f && r.highlightClipping < 0.018f -> Recipe(
                name = "Low-light cinematic rescue",
                reason = "Dark image with recoverable highlights; open important tones while preserving night mood and controlling noise.",
                exposureBias = 0.05f,
                contrastBias = 0.02f,
                shadowBias = 0.12f,
                vibranceBias = 0.02f,
                clarityBias = -0.005f,
                curveStrength = 0.08f,
                midtoneLift = 0.10f,
                highlightSoftness = 0.08f,
                gamma = 1.04f,
                subjectLift = 0.20f,
                subjectContrast = 0.12f,
                backgroundCalm = 0.12f,
                edgeVignette = 0.12f,
                centerFocus = 0.18f
            )
            r.averageLuminance > 0.62f && r.dynamicRange < 0.50f -> Recipe(
                name = "Airy clean high-key refinement",
                reason = "Bright low-contrast image; keep the clean feeling but add shape, believable whites, and gentle color separation.",
                exposureBias = -0.03f,
                contrastBias = 0.05f,
                highlightBias = -0.10f,
                shadowBias = -0.02f,
                vibranceBias = 0.04f,
                saturationBias = 0.00f,
                curveStrength = 0.07f,
                highlightSoftness = 0.16f,
                gamma = 0.98f,
                skyRecovery = 0.16f,
                backgroundCalm = 0.10f,
                centerFocus = 0.08f
            )
            r.natureLikelihood > 0.22f -> Recipe(
                name = "Natural color depth",
                reason = "Green/nature-heavy palette; add depth and freshness while preventing neon greens and fake saturation.",
                contrastBias = 0.04f,
                vibranceBias = 0.04f,
                saturationBias = -0.01f,
                clarityBias = 0.018f,
                dehazeBias = 0.02f,
                curveStrength = 0.11f,
                colorSeparation = 0.07f,
                backgroundCalm = 0.12f,
                foregroundDepth = 0.08f,
                heroColorRichness = 0.10f
            )
            r.dynamicRange > 0.78f && r.shadowClipping < 0.04f -> Recipe(
                name = "Dramatic range sculpt",
                reason = "Strong natural contrast; sculpt depth and highlights carefully instead of flattening into HDR.",
                contrastBias = 0.02f,
                highlightBias = -0.10f,
                shadowBias = 0.03f,
                vibranceBias = 0.02f,
                curveStrength = 0.14f,
                colorSeparation = 0.05f,
                highlightSoftness = 0.12f,
                edgeVignette = 0.14f,
                foregroundDepth = 0.10f,
                backgroundCalm = 0.10f
            )
            abs(r.warmSceneStrength - r.coolSceneStrength) > 0.16f -> Recipe(
                name = "Atmosphere-preserving color correction",
                reason = "Strong scene color mood detected; correct cast without killing the atmosphere that makes the photo unique.",
                contrastBias = 0.03f,
                vibranceBias = 0.03f,
                saturationBias = 0.00f,
                curveStrength = 0.09f,
                colorSeparation = 0.06f,
                backgroundCalm = 0.10f
            )
            else -> Recipe(
                name = "Gallery-grade balanced polish",
                reason = "Balanced image; apply subtle light shaping, color cleanup, and detail polish so it looks edited but not artificial.",
                contrastBias = 0.02f,
                vibranceBias = 0.03f,
                saturationBias = 0.00f,
                curveStrength = 0.08f,
                midtoneLift = 0.03f,
                highlightSoftness = 0.08f,
                colorSeparation = 0.04f,
                subjectLift = 0.14f,
                subjectContrast = 0.08f,
                backgroundCalm = 0.12f,
                edgeVignette = 0.10f,
                centerFocus = 0.12f
            )
        }
    }
}

private data class Recipe(
    val name: String,
    val reason: String,
    val exposureBias: Float = 0f,
    val contrastBias: Float = 0f,
    val highlightBias: Float = 0f,
    val shadowBias: Float = 0f,
    val temperatureBias: Float = 0f,
    val tintBias: Float = 0f,
    val vibranceBias: Float = 0f,
    val saturationBias: Float = 0f,
    val clarityBias: Float = 0f,
    val dehazeBias: Float = 0f,
    val curveStrength: Float = 0f,
    val midtoneLift: Float = 0f,
    val highlightSoftness: Float = 0f,
    val colorSeparation: Float = 0f,
    val gamma: Float = 1f,
    val subjectLift: Float = 0f,
    val subjectContrast: Float = 0f,
    val backgroundCalm: Float = 0f,
    val skyRecovery: Float = 0f,
    val foregroundDepth: Float = 0f,
    val edgeVignette: Float = 0f,
    val centerFocus: Float = 0f,
    val heroColorRichness: Float = 0f
)
