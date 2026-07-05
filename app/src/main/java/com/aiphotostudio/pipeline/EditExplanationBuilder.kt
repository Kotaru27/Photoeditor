package com.aiphotostudio.pipeline

import com.aiphotostudio.editgraph.GeometryOperation

/**
 * Converts internal scene understanding into concise user-facing edit explanations.
 * The app should feel like it understood the photo, not like it applied a random preset.
 */
object EditExplanationBuilder {
    fun build(profile: SceneUnderstandingProfile, candidateName: String, geometry: GeometryOperation = GeometryOperation()): EditDecisionSummary {
        val observations = mutableListOf<String>()
        val actions = mutableListOf<String>()

        when (profile.dominant) {
            SceneDominant.PERSON -> {
                observations += "person-focused scene"
                actions += "skin protected"
                actions += "background calmed"
                if (profile.backlitLikelihood > 0.35f || profile.skyHeavyLikelihood > 0.25f) actions += "highlights softened"
            }
            SceneDominant.OBJECT_MATERIAL -> {
                observations += "detailed foreground object"
                actions += "object detail enhanced"
                if (profile.backgroundClutter > 0.18f) actions += "background calmed"
                if (profile.skyHeavyLikelihood > 0.20f) actions += "sky protected"
                if (profile.objectMaterialLikelihood > 0.16f) actions += "material depth improved"
            }
            SceneDominant.SKY_LANDSCAPE -> {
                observations += "sky-heavy scene"
                actions += "sky recovered"
                actions += "foreground depth balanced"
                if (profile.natureLikelihood > 0.22f) actions += "greens kept natural"
            }
            SceneDominant.NATURE -> {
                observations += "green/nature scene"
                actions += "natural color depth added"
                actions += "greens controlled"
                if (profile.subjectStrength > 0.22f) actions += "subject separated"
            }
            SceneDominant.LOW_LIGHT -> {
                observations += "low-light scene"
                actions += "important shadows lifted"
                actions += "noise protected"
                actions += "mood preserved"
            }
            SceneDominant.GENERAL -> {
                observations += "balanced scene"
                actions += "light shaped"
                if (profile.subjectStrength > 0.20f) actions += "subject emphasized"
                if (profile.backgroundClutter > 0.20f) actions += "background calmed"
            }
        }

        if (profile.emptyTopPressure > 0.28f && !actions.contains("sky protected")) actions += "bright top controlled"
        if (profile.dramaticContrastLikelihood > 0.70f) actions += "contrast preserved"
        if (profile.flatLightLikelihood > 0.55f) actions += "depth added"

        // v1.4.4: mention framing explicitly whenever Auto Frame actually cropped the photo,
        // since Before/After share the same crop and the user may otherwise not realize
        // Auto Frame ran at all.
        if (geometry.cropTop > 0.015f || geometry.cropLeft > 0.015f || geometry.cropRight < 0.985f) {
            actions.add(0, "framing improved")
        }

        val shortSummary = actions.distinct().take(3).joinToString(" · ").ifBlank { "Professional edit selected" }
        val detail = buildString {
            append("Detected ")
            append(observations.distinct().take(2).joinToString(" and ").ifBlank { "the scene structure" })
            append("; selected ")
            append(candidateName)
            append(" to ")
            append(actions.distinct().take(4).joinToString(", ").ifBlank { "improve the image naturally" })
            append(".")
        }
        return EditDecisionSummary(
            shortSummary = shortSummary,
            detail = detail,
            selectedCandidate = candidateName,
            dominantScene = profile.dominant.name
        )
    }
}

data class EditDecisionSummary(
    val shortSummary: String,
    val detail: String,
    val selectedCandidate: String,
    val dominantScene: String
)

