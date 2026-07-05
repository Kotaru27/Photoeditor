package com.aiphotostudio.pipeline

import android.graphics.Bitmap
import android.graphics.Color
import com.aiphotostudio.editgraph.EditGraph
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

/**
 * Real image processing only. No generated pixels, no object replacement, no hallucinated detail.
 * All output pixels are transformed from the original photograph using deterministic photographic math.
 */
object RenderGraphExecutor {
    /**
     * v1.4.5: Compose (crop) now happens FIRST, before tone/color/local-light shaping,
     * matching the senior-editor sequence Compose -> Correct -> Shape Light -> Grade Color.
     * Previously the crop was applied last, so every local mask (subject/background/sky/
     * foreground/edge/vignette) was computed against the pre-crop frame -- meaning a pixel
     * that ends up at the very top edge of a cropped photo was still being shaped as if it
     * were still deep inside the original, uncropped frame. That silently mis-targets local
     * light shaping on every photo where Auto Frame actually crops.
     */
    fun render(source: Bitmap, graph: EditGraph): Bitmap {
        val composed = applyGeometry(source, graph)
        val width = composed.width
        val height = composed.height
        val out = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val pixels = IntArray(width * height)
        composed.getPixels(pixels, 0, width, 0, 0, width, height)

        val exposureMul = 2.0.pow(graph.tone.exposure.toDouble()).toFloat()
        val contrast = graph.tone.contrast.coerceIn(-0.5f, 0.5f)
        val gamma = graph.tone.gamma.coerceIn(0.7f, 1.4f)
        val curve = graph.tone.curveStrength.coerceIn(-0.2f, 0.28f)
        val midLift = graph.tone.midtoneLift.coerceIn(0f, 0.20f)
        val highlightSoftness = graph.tone.highlightSoftness.coerceIn(0f, 0.28f)

        for (y in 0 until height) {
            for (x in 0 until width) {
                val i = y * width + x
                val c = pixels[i]
                val a = Color.alpha(c)
                var r = Color.red(c) / 255f
                var g = Color.green(c) / 255f
                var b = Color.blue(c) / 255f

            r *= exposureMul; g *= exposureMul; b *= exposureMul

            val lum = luminance(r, g, b)
            val shadowMask = 1f - smoothstep(0.16f, 0.55f, lum)
            val highlightMask = smoothstep(0.45f, 0.88f, lum)
            val whiteMask = smoothstep(0.78f, 1.0f, lum)
            val blackMask = 1f - smoothstep(0.0f, 0.22f, lum)
            val midMask = 1f - abs(lum - 0.5f).coerceIn(0f, 0.5f) * 2f

            val toneDelta = graph.tone.shadows * 0.20f * shadowMask +
                graph.tone.highlights * 0.18f * highlightMask +
                graph.tone.whites * 0.10f * whiteMask +
                graph.tone.blacks * 0.10f * blackMask +
                midLift * 0.12f * midMask
            r += toneDelta; g += toneDelta; b += toneDelta

            r = ((r - 0.5f) * (1f + contrast) + 0.5f)
            g = ((g - 0.5f) * (1f + contrast) + 0.5f)
            b = ((b - 0.5f) * (1f + contrast) + 0.5f)

            if (curve != 0f) {
                r = applyPhotographicCurve(r, curve)
                g = applyPhotographicCurve(g, curve)
                b = applyPhotographicCurve(b, curve)
            }

            if (highlightSoftness > 0f) {
                r = softenHighlightShoulder(r, highlightSoftness)
                g = softenHighlightShoulder(g, highlightSoftness)
                b = softenHighlightShoulder(b, highlightSoftness)
            }

            if (gamma != 1f) {
                r = safePow(r, 1f / gamma); g = safePow(g, 1f / gamma); b = safePow(b, 1f / gamma)
            }

            // Conservative white-balance/channel correction.
            val temp = graph.color.temperature.coerceIn(-0.3f, 0.3f)
            val tint = graph.color.tint.coerceIn(-0.25f, 0.25f)
            r *= 1f + temp * 0.11f + tint * 0.05f
            b *= 1f - temp * 0.11f + tint * 0.05f
            g *= 1f - tint * 0.08f

            val colorSeparation = graph.color.colorSeparation.coerceIn(0f, 0.18f)
            if (colorSeparation > 0f) {
                val warm = smoothstep(0.02f, 0.24f, r - b)
                val cool = smoothstep(0.02f, 0.24f, b - r)
                val green = smoothstep(0.02f, 0.22f, g - max(r, b))
                val skinLike = if (r > g && g > b && r - b > 0.08f) 1f else 0f
                val protect = if (graph.color.memoryColorProtect > 0.7f) skinLike * 0.65f else 0f
                val amount = colorSeparation * (1f - protect)
                r += warm * amount * 0.030f - cool * amount * 0.012f
                b += cool * amount * 0.030f - warm * amount * 0.012f
                g += green * amount * 0.018f
            }

            val currentLum = luminance(r, g, b)
            val maxCh = max(r, max(g, b))
            val minCh = min(r, min(g, b))
            val saturationLevel = (maxCh - minCh).coerceIn(0f, 1f)
            val satAdj = graph.color.saturation.coerceIn(-0.4f, 0.4f)
            val vibAdj = graph.color.vibrance.coerceIn(-0.4f, 0.4f)
            val skinLike = r > g && g > b && r - b > 0.08f
            val memoryProtection = if (skinLike && graph.color.memoryColorProtect > 0.7f) 0.55f else 1f
            val vibranceGain = vibAdj * (1f - saturationLevel) * 0.55f * memoryProtection
            val satGain = 1f + satAdj * memoryProtection + vibranceGain
            r = currentLum + (r - currentLum) * satGain
            g = currentLum + (g - currentLum) * satGain
            b = currentLum + (b - currentLum) * satGain

                // Dehaze/clarity is intentionally tiny to avoid HDR halos.
                val micro = (graph.detail.clarity * 0.08f + graph.detail.dehaze * 0.10f).coerceIn(0f, 0.035f)
                if (micro > 0f) {
                    r = ((r - 0.5f) * (1f + micro) + 0.5f)
                    g = ((g - 0.5f) * (1f + micro) + 0.5f)
                    b = ((b - 0.5f) * (1f + micro) + 0.5f)
                }

                val localLum = luminance(r, g, b)
                val skinProtectLocal = if (r > g && g > b && r - b > 0.07f && localLum > 0.28f && localLum < 0.88f) 0.55f else 1f
                val masks = localMasks(x, y, width, height, localLum)
                val subjectAmount = graph.local.subjectLift * masks.subject * skinProtectLocal
                if (subjectAmount > 0f) {
                    val lift = subjectAmount * 0.26f * (1f - smoothstep(0.68f, 0.94f, localLum))
                    r += lift; g += lift; b += lift
                }
                val subjectContrast = graph.local.subjectContrast * masks.subject
                if (subjectContrast > 0f) {
                    val contrastScale = 0.36f * skinProtectLocal
                    r = ((r - 0.5f) * (1f + subjectContrast * contrastScale) + 0.5f)
                    g = ((g - 0.5f) * (1f + subjectContrast * contrastScale) + 0.5f)
                    b = ((b - 0.5f) * (1f + subjectContrast * contrastScale) + 0.5f)
                }
                val heroRichness = graph.local.heroColorRichness * masks.subject
                if (heroRichness > 0f) {
                    val warmObject = smoothstep(0.04f, 0.24f, r - b) * smoothstep(0.16f, 0.72f, localLum) * (1f - smoothstep(0.88f, 1.0f, localLum))
                    val rich = heroRichness * warmObject
                    val heroLum = luminance(r, g, b)
                    r = heroLum + (r - heroLum) * (1f + rich * 0.36f)
                    g = heroLum + (g - heroLum) * (1f + rich * 0.26f)
                    b = heroLum + (b - heroLum) * (1f + rich * 0.16f)
                    r += rich * 0.014f
                    g += rich * 0.008f
                }
                val skyRecovery = graph.local.skyRecovery * masks.sky
                if (skyRecovery > 0f) {
                    val protect = skyRecovery * (0.45f + 0.55f * smoothstep(0.50f, 1.0f, localLum))
                    r -= protect * 0.16f; g -= protect * 0.16f; b -= protect * 0.14f
                    r = softenHighlightShoulder(r, protect * 0.9f)
                    g = softenHighlightShoulder(g, protect * 0.9f)
                    b = softenHighlightShoulder(b, protect * 0.9f)
                }
                val calm = graph.local.backgroundCalm * masks.background
                if (calm > 0f) {
                    val bgLum = luminance(r, g, b)
                    r = bgLum + (r - bgLum) * (1f - calm * 0.52f)
                    g = bgLum + (g - bgLum) * (1f - calm * 0.52f)
                    b = bgLum + (b - bgLum) * (1f - calm * 0.52f)
                    r -= calm * 0.060f; g -= calm * 0.060f; b -= calm * 0.060f
                }
                val foregroundDepth = graph.local.foregroundDepth * masks.foreground
                if (foregroundDepth > 0f) {
                    r = ((r - 0.5f) * (1f + foregroundDepth * 0.20f) + 0.5f) - foregroundDepth * 0.036f
                    g = ((g - 0.5f) * (1f + foregroundDepth * 0.20f) + 0.5f) - foregroundDepth * 0.036f
                    b = ((b - 0.5f) * (1f + foregroundDepth * 0.20f) + 0.5f) - foregroundDepth * 0.036f
                }
                val vignette = (graph.local.edgeVignette * masks.edge + graph.local.centerFocus * masks.centerFocusEdge).coerceIn(0f, 0.22f)
                if (vignette > 0f) {
                    val darken = vignette * 0.28f
                    r *= 1f - darken; g *= 1f - darken; b *= 1f - darken
                }

                if (skinProtectLocal < 1f) {
                    val skinLum = luminance(r, g, b)
                    val hot = smoothstep(0.62f, 0.92f, skinLum)
                    r -= hot * 0.055f
                    g -= hot * 0.035f
                    b -= hot * 0.018f
                }

                pixels[i] = Color.argb(a, toByte(r), toByte(g), toByte(b))
            }
        }

        val detailed = applyDetailPass(pixels, width, height, graph)
        out.setPixels(detailed, 0, width, 0, 0, width, height)
        return out
    }

    /**
     * Produces the fair Before preview when Auto Frame is active.
     * It does not apply tone, color, detail, or local edits — only the same non-destructive
     * geometry crop used by the rendered After image, so Before/After comparison does not jump.
     */
    fun renderOriginalFrame(source: Bitmap, graph: EditGraph): Bitmap = applyGeometry(source, graph)

    private fun applyGeometry(bitmap: Bitmap, graph: EditGraph): Bitmap {
        val left = (graph.geometry.cropLeft.coerceIn(0f, 0.25f) * bitmap.width).toInt()
        val top = (graph.geometry.cropTop.coerceIn(0f, 0.32f) * bitmap.height).toInt()
        val right = (graph.geometry.cropRight.coerceIn(0.75f, 1f) * bitmap.width).toInt()
        val bottom = (graph.geometry.cropBottom.coerceIn(0.75f, 1f) * bitmap.height).toInt()
        val cropWidth = (right - left).coerceAtLeast(bitmap.width / 2)
        val cropHeight = (bottom - top).coerceAtLeast(bitmap.height / 2)
        if (left == 0 && top == 0 && cropWidth == bitmap.width && cropHeight == bitmap.height) return bitmap
        return Bitmap.createBitmap(bitmap, left, top, cropWidth, cropHeight)
    }

    private fun applyDetailPass(input: IntArray, width: Int, height: Int, graph: EditGraph): IntArray {
        val sharpness = graph.detail.sharpness.coerceIn(0f, 0.18f)
        val texture = graph.detail.texture.coerceIn(0f, 0.12f)
        val noiseReduction = graph.detail.noiseReduction.coerceIn(0f, 0.35f)
        if (sharpness <= 0.001f && texture <= 0.001f && noiseReduction <= 0.001f) return input

        val out = input.copyOf()
        val sharpenAmount = (sharpness * 0.75f + texture * 0.35f).coerceIn(0f, 0.16f)
        val nrAmount = noiseReduction.coerceIn(0f, 0.25f)

        for (y in 1 until height - 1) {
            val row = y * width
            for (x in 1 until width - 1) {
                val idx = row + x
                val c = input[idx]
                val left = input[idx - 1]
                val right = input[idx + 1]
                val up = input[idx - width]
                val down = input[idx + width]
                val avgR = (Color.red(left) + Color.red(right) + Color.red(up) + Color.red(down)) / 4f
                val avgG = (Color.green(left) + Color.green(right) + Color.green(up) + Color.green(down)) / 4f
                val avgB = (Color.blue(left) + Color.blue(right) + Color.blue(up) + Color.blue(down)) / 4f

                val r = Color.red(c).toFloat()
                val g = Color.green(c).toFloat()
                val b = Color.blue(c).toFloat()
                val edge = (abs(luma255(c) - ((luma255(left) + luma255(right) + luma255(up) + luma255(down)) / 4f)) / 255f).coerceIn(0f, 1f)

                val smoothMask = 1f - smoothstep(0.025f, 0.14f, edge)
                val edgeMask = smoothstep(0.035f, 0.22f, edge) * graph.detail.haloGuard.coerceIn(0.4f, 1f)
                val nr = nrAmount * smoothMask
                val sh = sharpenAmount * edgeMask

                var rr = r * (1f - nr) + avgR * nr
                var gg = g * (1f - nr) + avgG * nr
                var bb = b * (1f - nr) + avgB * nr

                rr += (r - avgR) * sh
                gg += (g - avgG) * sh
                bb += (b - avgB) * sh

                out[idx] = Color.argb(Color.alpha(c), toByte255(rr), toByte255(gg), toByte255(bb))
            }
        }
        return out
    }


    private data class LocalMasks(
        val subject: Float,
        val background: Float,
        val sky: Float,
        val foreground: Float,
        val edge: Float,
        val centerFocusEdge: Float
    )

    private fun localMasks(x: Int, y: Int, width: Int, height: Int, lum: Float): LocalMasks {
        val nx = x.toFloat() / width.toFloat()
        val ny = y.toFloat() / height.toFloat()
        val dx = (nx - 0.5f) / 0.40f
        val dy = (ny - 0.57f) / 0.46f
        val centerDistance = kotlin.math.sqrt(dx * dx + dy * dy)
        val subject = (1f - smoothstep(0.62f, 1.34f, centerDistance)) * (1f - smoothstep(0.86f, 1.0f, lum))
        val background = (1f - subject * 0.85f) * smoothstep(0.14f, 0.58f, kotlin.math.abs(nx - 0.5f) + kotlin.math.abs(ny - 0.5f))
        val sky = (1f - smoothstep(0.34f, 0.62f, ny)) * smoothstep(0.52f, 0.92f, lum)
        val foreground = smoothstep(0.58f, 1.0f, ny) * (1f - subject * 0.65f)
        val edgeDist = kotlin.math.max(kotlin.math.abs(nx - 0.5f), kotlin.math.abs(ny - 0.5f)) * 2f
        val edge = smoothstep(0.62f, 1.0f, edgeDist) * (1f - sky * 0.65f)
        val centerFocusEdge = edge * (1f - subject * 0.85f) * (1f - sky * 0.80f)
        return LocalMasks(subject.coerceIn(0f, 1f), background.coerceIn(0f, 1f), sky.coerceIn(0f, 1f), foreground.coerceIn(0f, 1f), edge.coerceIn(0f, 1f), centerFocusEdge.coerceIn(0f, 1f))
    }

    private fun applyPhotographicCurve(v: Float, strength: Float): Float {
        val x = v.coerceIn(0f, 1f)
        val s = x * x * (3f - 2f * x)
        return (x + (s - x) * strength).coerceIn(0f, 1f)
    }

    private fun softenHighlightShoulder(v: Float, amount: Float): Float {
        val x = v.coerceIn(0f, 1.4f)
        if (x <= 0.72f) return x.coerceIn(0f, 1f)
        val compressed = 0.72f + (x - 0.72f) / (1f + amount * 2.4f * (x - 0.72f))
        return compressed.coerceIn(0f, 1f)
    }

    private fun luminance(r: Float, g: Float, b: Float): Float = 0.2126f * r + 0.7152f * g + 0.0722f * b
    private fun luma255(c: Int): Float = 0.2126f * Color.red(c) + 0.7152f * Color.green(c) + 0.0722f * Color.blue(c)
    private fun smoothstep(edge0: Float, edge1: Float, x: Float): Float {
        val t = ((x - edge0) / (edge1 - edge0)).coerceIn(0f, 1f)
        return t * t * (3f - 2f * t)
    }
    private fun safePow(v: Float, p: Float): Float = v.coerceIn(0f, 1f).pow(p)
    private fun toByte(v: Float): Int = (v.coerceIn(0f, 1f) * 255f + 0.5f).toInt().coerceIn(0, 255)
    private fun toByte255(v: Float): Int = (v + 0.5f).toInt().coerceIn(0, 255)
}
