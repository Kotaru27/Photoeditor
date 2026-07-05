package com.aiphotostudio.pipeline

import android.graphics.Bitmap
import android.graphics.Color
import com.aiphotostudio.editgraph.EditGraph
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * v1.3.0 mask-based local grading layer.
 *
 * Uses dense analysis maps as masks to apply local subject/object/background/sky/skin/green
 * refinements after the base RenderGraphExecutor output. Still non-generative: every output
 * pixel is a deterministic transformation of the existing image pixels.
 */
object LocalGradingRenderer {
    fun render(source: Bitmap, graph: EditGraph): Bitmap {
        val base = RenderGraphExecutor.render(source, graph)
        val dense = DensePixelAnalyzer.analyze(base)
        val width = base.width
        val height = base.height
        val pixels = IntArray(width * height)
        base.getPixels(pixels, 0, width, 0, 0, width, height)

        for (y in 0 until height) {
            for (x in 0 until width) {
                val idx = y * width + x
                val dIdx = denseIndex(dense, x, y, width, height)
                val c = pixels[idx]
                val a = Color.alpha(c)
                var r = Color.red(c) / 255f
                var g = Color.green(c) / 255f
                var b = Color.blue(c) / 255f

                val saliency = dense.saliency[dIdx]
                val distraction = dense.distraction[dIdx]
                val sky = dense.skyLikelihood[dIdx]
                val skin = dense.skinLikelihood[dIdx]
                val green = dense.greenLikelihood[dIdx]
                val warmObject = dense.warmObjectLikelihood[dIdx]
                val highlight = dense.highlight[dIdx]
                val shadow = dense.shadow[dIdx]
                val texture = dense.texture[dIdx]

                val subjectMask = (saliency * (1f - distraction * 0.40f) * (1f - sky * 0.75f)).coerceIn(0f, 1f)
                val objectMask = (warmObject * (0.45f + texture * 0.55f) * (1f - skin * 0.55f)).coerceIn(0f, 1f)
                val backgroundMask = ((1f - subjectMask * 0.75f) * max(distraction, green * 0.55f) * (1f - skin * 0.65f)).coerceIn(0f, 1f)
                val skyMask = sky.coerceIn(0f, 1f)
                val greenMask = (green * (1f - subjectMask * 0.35f)).coerceIn(0f, 1f)
                val skinMask = skin.coerceIn(0f, 1f)

                // Sky/highlight rolloff: reduce dominance smoothly without patchy gray artifacts.
                if (skyMask > 0.001f) {
                    val amount = skyMask * (0.10f + highlight * 0.08f)
                    r -= amount * 0.10f
                    g -= amount * 0.10f
                    b -= amount * 0.08f
                    val lum = luminance(r, g, b)
                    val soft = smoothstep(0.58f, 0.95f, lum) * skyMask
                    r = mix(r, lum, soft * 0.10f)
                    g = mix(g, lum, soft * 0.10f)
                    b = mix(b, lum, soft * 0.08f)
                }

                // Background calming: lower color/contrast/luminance on distractions and active greens.
                if (backgroundMask > 0.001f) {
                    val lum = luminance(r, g, b)
                    val calm = backgroundMask * 0.22f
                    r = lum + (r - lum) * (1f - calm)
                    g = lum + (g - lum) * (1f - calm)
                    b = lum + (b - lum) * (1f - calm)
                    r -= backgroundMask * 0.025f
                    g -= backgroundMask * 0.025f
                    b -= backgroundMask * 0.025f
                }

                // Green control: reduce neon/active greens, keep natural vegetation feeling.
                if (greenMask > 0.001f) {
                    val lum = luminance(r, g, b)
                    val amt = greenMask * 0.18f
                    g = mix(g, lum, amt * 0.45f)
                    r = mix(r, lum, amt * 0.10f)
                    b = mix(b, lum, amt * 0.10f)
                    g -= amt * 0.018f
                }

                // Warm material/object grading: richer depth and texture without fake yellowing.
                if (objectMask > 0.001f) {
                    val lum = luminance(r, g, b)
                    val detail = objectMask * (0.18f + texture * 0.16f)
                    r = ((r - 0.48f) * (1f + detail) + 0.48f)
                    g = ((g - 0.48f) * (1f + detail * 0.85f) + 0.48f)
                    b = ((b - 0.48f) * (1f + detail * 0.55f) + 0.48f)
                    val rich = objectMask * (1f - highlight * 0.45f) * 0.055f
                    r += rich * 0.70f
                    g += rich * 0.38f
                    b -= rich * 0.20f
                    // Keep material depth by gently darkening deepest object texture.
                    val deepen = objectMask * shadow * 0.035f
                    r -= deepen; g -= deepen; b -= deepen
                    // Prevent flat yellow clipping on bright base areas.
                    val hot = objectMask * highlight * 0.055f
                    r -= hot * 0.55f
                    g -= hot * 0.35f
                }

                // Subject shaping: subtle midtone lift and separation.
                if (subjectMask > 0.001f) {
                    val lum = luminance(r, g, b)
                    val mid = (1f - abs(lum - 0.50f) * 2f).coerceIn(0f, 1f)
                    val lift = subjectMask * mid * (1f - skinMask * 0.55f) * 0.040f
                    r += lift; g += lift; b += lift
                }

                // Skin protection: compress hot skin and reduce over-clarity side effects.
                if (skinMask > 0.001f) {
                    val lum = luminance(r, g, b)
                    val hot = skinMask * smoothstep(0.60f, 0.90f, lum)
                    r -= hot * 0.045f
                    g -= hot * 0.030f
                    b -= hot * 0.014f
                }

                pixels[idx] = Color.argb(a, toByte(r), toByte(g), toByte(b))
            }
        }

        return Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).also {
            it.setPixels(pixels, 0, width, 0, 0, width, height)
        }
    }

    private fun denseIndex(dense: DenseAnalysisMap, x: Int, y: Int, width: Int, height: Int): Int {
        val dx = ((x.toFloat() / max(1, width - 1)) * (dense.width - 1)).toInt().coerceIn(0, dense.width - 1)
        val dy = ((y.toFloat() / max(1, height - 1)) * (dense.height - 1)).toInt().coerceIn(0, dense.height - 1)
        return dy * dense.width + dx
    }

    private fun luminance(r: Float, g: Float, b: Float): Float = 0.2126f * r + 0.7152f * g + 0.0722f * b
    private fun mix(a: Float, b: Float, t: Float): Float = a + (b - a) * t.coerceIn(0f, 1f)
    private fun smoothstep(edge0: Float, edge1: Float, x: Float): Float {
        val t = ((x - edge0) / (edge1 - edge0)).coerceIn(0f, 1f)
        return t * t * (3f - 2f * t)
    }
    private fun toByte(v: Float): Int = (v.coerceIn(0f, 1f) * 255f + 0.5f).toInt().coerceIn(0, 255)
}
