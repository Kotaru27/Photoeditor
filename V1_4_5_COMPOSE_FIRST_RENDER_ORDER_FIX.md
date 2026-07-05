# AI Photo Studio v1.4.5 — Compose-First Render Order Fix

Date: 2026-07-05

## Version

```text
versionCode = 145
versionName = "1.4.5"
```

App label:

```text
V1.4.5
```

Launcher/app name:

```text
AI Photo Studio v1.4.5
```

GitHub artifact/file (auto-named by the build workflow from `versionName`):

```text
AI-Photo-Studio-v1.4.5-debug-apk
AIPhotoStudio-v1.4.5-debug.apk
```

## What this update is

This is a scoped, single-feature fix per the project's Implementation Mode rule
(implement one feature only, modify the minimum required files, stop). It does
**not** touch Auto Frame's crop *decision* logic (`AutoFrameEngine.kt` is
unmodified) and does **not** start any v1.5.0 color-grading work. It fixes the
**order of operations** inside the renderer to match the senior-editor workflow
this project is explicitly modeling: `Compose -> Correct -> Shape Light -> Grade
Color -> Separate Subject/Background -> Polish Detail -> Final Check`.

## The bug

In `RenderGraphExecutor.render()`, the crop (`applyGeometry`) was applied **last**,
after all tone/color/local-light processing had already run on the full,
uncropped source bitmap. The local light-shaping masks (`localMasks(...)` —
subject, background, sky, foreground, edge/vignette) are computed from pixel
coordinates normalized against the current bitmap's width/height
(`x / width`, `y / height`).

Because those masks were calculated on the pre-crop frame and the crop happened
afterward, every photo where Auto Frame actually crops ended up with local
light shaping computed for the *wrong* frame. For example: if Auto Frame crops
the top 20% of sky, a pixel that ends up at row 0 of the final composed image
was shaped as if it were still 20% down from the top of the original,
uncropped photo — meaning sky/vignette/subject-position-aware shaping was
silently miscalculated on every cropped image.

This is a direct violation of "Compose first" from the senior-editor research
in this project's reference material (composition should be locked in before
light shaping, because it "establishes the geometric proportions for the
edit").

## The fix

`RenderGraphExecutor.render()` now applies `applyGeometry(source, graph)`
**first**, then runs tone/color/local-light/detail processing on the already-composed
(cropped) bitmap. The final `applyGeometry` call at the end of the old
pipeline was removed since the crop is no longer needed a second time.

`renderOriginalFrame()` (used for the fair Before/After preview) is
unchanged — it already only applied geometry, so it still produces the exact
same crop as before.

`LocalGradingRenderer` required no change: it already calls
`DensePixelAnalyzer.analyze(base)` on the *output* of
`RenderGraphExecutor.render()`, so once that output is correctly composed
first, `LocalGradingRenderer`'s own mask-based grading pass automatically
inherits the fix — it was already analyzing the right frame, it just needed
`RenderGraphExecutor` to hand it the right frame to begin with.

## Files changed

```text
app/src/main/java/com/aiphotostudio/pipeline/RenderGraphExecutor.kt   (logic fix)
app/build.gradle.kts                                                   (versionCode/versionName)
app/src/main/res/values/strings.xml                                    (app_name)
app/src/main/java/com/aiphotostudio/MainActivity.kt                    (header version label)
README.md                                                               (version + changelog reference)
```

No other files were modified. `AutoFrameEngine.kt` (crop decision logic) is
untouched — this fix only corrects what happens *after* a crop decision has
already been made, not whether/how much to crop.

## Why this doesn't require re-litigating Auto Frame

This fix is independent of whatever Auto Frame decides. If Auto Frame crops
0%, this fix is a no-op (no behavior change — `applyGeometry` returns the
same bitmap unchanged when there's nothing to crop). If Auto Frame crops any
amount, this fix ensures the resulting light shaping is calculated correctly
for the frame the user will actually see, rather than for a frame that no
longer exists after cropping.

## What to test

1. **A photo where Auto Frame crops nothing** (should look visually identical
   to v1.4.4 — this is a pure correctness fix, not a strength change).
2. **The standard test photo** (bright top sky + foreground object) where
   Auto Frame is expected to crop — check whether vignette/sky/subject
   shaping now looks correctly positioned relative to the new, smaller frame
   (e.g. vignette should hug the new edges, not the old ones; sky shaping
   should apply to what's left of the sky in the cropped frame).
3. Confirm no crash, no visual regression, no unexpected behavior change on a
   plain uncropped photo.

## What this does NOT fix

This does not make Auto Frame crop more or less — that decision logic is
unchanged and still needs its own real-device verification (open item from
v1.4.4). This also does not touch color grading (still RGB-based, v1.5.0
scope) or detail/base-separation (also v1.5.0 scope). This is purely a
render-order correctness fix.

## Review checklist (fill in after testing on device)

```text
Review Mode — v1.4.5: Compose-First Render Order Fix

Result: PASS / FAIL

Evidence:
- [Screenshot: uncropped photo, before/after v1.4.5 — should be visually identical]
- [Screenshot: cropped photo (Auto Frame active), before/after v1.4.5 — check vignette/sky/subject shaping alignment]
- [Any crash logs, if applicable]

Issues found:
- [Blocking]
- [Non-blocking]

Required fixes before next sprint:
- [List]
```
