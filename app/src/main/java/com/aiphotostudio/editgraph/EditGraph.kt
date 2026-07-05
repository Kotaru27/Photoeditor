package com.aiphotostudio.editgraph

/**
 * Non-destructive edit description.
 * The source photo is never modified. Rendering is always source bitmap + EditGraph.
 *
 * This graph describes a professional photographic treatment, not a generic filter.
 * The intent fields explain why the automatic edit was chosen for this exact image.
 */
data class EditGraph(
    val version: Int = 2,
    val professionalIntent: String = "Natural professional correction",
    val intentReason: String = "Balanced restrained edit based on image measurements.",
    val tone: ToneOperation = ToneOperation(),
    val color: ColorOperation = ColorOperation(),
    val detail: DetailOperation = DetailOperation(),
    val local: LocalLightOperation = LocalLightOperation(),
    val geometry: GeometryOperation = GeometryOperation()
)

data class ToneOperation(
    val exposure: Float = 0f,          // stops, safe range roughly -1.0..1.0
    val contrast: Float = 0f,          // -1..1
    val highlights: Float = 0f,        // -1..1
    val shadows: Float = 0f,           // -1..1
    val whites: Float = 0f,            // -1..1
    val blacks: Float = 0f,            // -1..1
    val gamma: Float = 1f,             // 0.7..1.4
    val curveStrength: Float = 0f,     // restrained photographic S-curve, -1..1
    val midtoneLift: Float = 0f,       // protects important midtones from looking dull
    val highlightSoftness: Float = 0f  // reduces harsh digital-looking highlights
)

data class ColorOperation(
    val temperature: Float = 0f,       // -1 cool .. +1 warm
    val tint: Float = 0f,              // -1 green .. +1 magenta
    val vibrance: Float = 0f,          // -1..1
    val saturation: Float = 0f,        // -1..1
    val colorSeparation: Float = 0f,   // subtle separation, not a filter
    val memoryColorProtect: Float = 0f // keeps believable skies/skin/greens
)

data class DetailOperation(
    val clarity: Float = 0f,
    val texture: Float = 0f,
    val sharpness: Float = 0f,
    val noiseReduction: Float = 0f,
    val dehaze: Float = 0f,
    val haloGuard: Float = 1f          // prevents overdone HDR/edge halos
)

/**
 * Automatic senior-editor local intent.
 * This is still real image processing: no masks from generation, no new content.
 * It uses measured position/luminance/detail to guide the eye like an editor would.
 */
data class LocalLightOperation(
    val subjectLift: Float = 0f,       // brighten likely subject area gently
    val subjectContrast: Float = 0f,   // give likely subject shape/depth
    val backgroundCalm: Float = 0f,    // reduce distracting background dominance
    val skyRecovery: Float = 0f,       // protect bright upper-frame sky/highlights
    val foregroundDepth: Float = 0f,   // add weight to lower frame when useful
    val edgeVignette: Float = 0f,      // subtle eye-guiding edge darkening
    val centerFocus: Float = 0f,       // center-weighted focus, restrained
    val heroColorRichness: Float = 0f  // selective richness for important warm/object tones
)

data class GeometryOperation(
    val rotationDegrees: Float = 0f,
    val straightenDegrees: Float = 0f,
    val cropLeft: Float = 0f,
    val cropTop: Float = 0f,
    val cropRight: Float = 1f,
    val cropBottom: Float = 1f
)
