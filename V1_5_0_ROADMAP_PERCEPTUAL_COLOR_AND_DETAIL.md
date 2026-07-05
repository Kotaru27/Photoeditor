# AI Photo Studio — v1.5.0 Roadmap: Perceptual Color Grading & Detail Separation

Date added: 2026-07-05
Status: **Planned, not yet implemented.** This file is referenced by `README.md` under "Planned next" and is reconstructed here from the project's agreed direction (see `AI_PHOTO_STUDIO_MASTER_DIRECTION_AND_FAILURE_CHECKLIST.md` and the deep-research discussion history) so the README link is no longer dangling.

**Do not start this work until v1.4.4's Auto Frame behavior has been re-verified on real photos.** Per the project's Development Workflow rule, one feature is implemented and reviewed (PASS/FAIL with evidence) before the next sprint begins. Framing is still an open, unconfirmed item as of this writing — see `AI_PHOTO_STUDIO_MASTER_CONTEXT.md` §10.

---

## 1. Why this is next (not sooner, not skipped)

Across v1.1.0 → v1.4.4 the project fixed, in order: shallow single-recipe guessing (→ multi-candidate engine), then classification errors (→ scene understanding profile), then composition/cropping (→ Auto Frame 2.0–2.4, still being verified). The one major weakness that has been diagnosed repeatedly but never addressed at the engine level is:

> The renderer still does tone/color math almost entirely in raw RGB space, with simple local-contrast approximations for "detail enhancement." This is why edits still look like *correction* rather than *grading*, and why object/material texture (e.g. brass carvings) doesn't pop the way a real edit would.

This was diagnosed independently from multiple angles in the project history: candidate scoring, quality judging, and direct visual review of test photos all converged on the same root cause.

## 2. Scope of v1.5.0

Three new components, added *inside* the frozen pipeline (Editing Strategy Engine / RenderGraphExecutor stage) — not a new parallel engine:

### 2.1 `PerceptualColorGradeEngine`
Move color operations from naive RGB channel math to a perceptual color model (OKLab/OKLCH-inspired: separates perceived lightness, chroma, and hue so a hue/chroma change doesn't accidentally shift perceived brightness the way raw RGB scaling does).

Must control, independently:
- green luminance/chroma (natural vegetation, not neon)
- warm object/material richness (brass/wood/food) without yellow clipping
- sky lightness/chroma (replaces the current flat RGB subtraction in `LocalGradingRenderer`)
- skin-safe chroma compression (protect memory colors)
- shadow / midtone / highlight color balance (split-toning style control)
- saturation compression (avoid oversaturation cliff-edges)

Implementation note: a full OKLab conversion is not required on day one. An interim LAB-like approximation is acceptable as a first step, with true OKLab/OKLCH as the target.

### 2.2 `BaseDetailSeparator` + `ObjectMaterialDetailEnhancer`
Add an edge-aware base/detail decomposition (guided-filter-style: smooth base layer + detail layer = original − base) so texture enhancement can be targeted and halo-free.

Used for:
- enhancing carvings/grooves/fine object texture without crunchy sharpening
- deepening micro-shadows on textured material without muddying
- protecting shiny highlights from clipping
- explicitly avoiding sharpening sky or skin regions (already-smooth masks should stay smooth)

This replaces the current simple local-contrast math in `LocalGradingRenderer`'s object mask branch.

### 2.3 `BackgroundDistractionReducer`
Currently, background calming is folded into `LocalGradingRenderer`'s inline mask math. Split it into its own component so it can be tuned and tested independently:
- reduce background saturation/contrast/luminance using the existing distraction + green masks
- suppress edge/railing/clutter distractions without crushing shadows
- preserve natural atmosphere (do not fully desaturate/darken — calm, not kill)

## 3. What v1.5.0 explicitly does NOT include

- No generative AI, no new pipeline stage, no new planner. This is renderer-internals work only, living inside the existing `RenderGraphExecutor` / `LocalGradingRenderer` stage.
- No manual color-grading UI/sliders. All perceptual grading remains fully automatic, driven by the same `SceneUnderstandingProfile` and dense/region maps already in place.
- No change to the multi-candidate selection logic itself (that stays as-is unless testing reveals a scoring gap once perceptual grading exists).
- No straightening/horizon correction (tracked separately, lower priority — see master context §10 item 8).

## 4. Suggested implementation order

1. `PerceptualColorGradeEngine` (color-space conversion + green/warm-object/sky/skin controls) — highest visual impact, addresses the most-repeated user complaint ("still looks like brightness/contrast, not grading").
2. `BackgroundDistractionReducer` extraction — lower risk, mostly a refactor of existing working logic into its own tunable component.
3. `BaseDetailSeparator` + `ObjectMaterialDetailEnhancer` — highest implementation complexity (edge-aware filtering), do last so the earlier two wins aren't blocked by it.

## 5. Acceptance check before calling v1.5.0 done

Per the project's Review Mode rule (report PASS/FAIL with evidence, no code changes during review):

- [ ] Object/material test photo: carving/texture depth visibly increases without halos or fake-yellow clipping.
- [ ] Sky no longer goes dull/gray when pushed (this was a known regression risk flagged in v1.4.4's changelog for the old flat RGB subtraction).
- [ ] Green backgrounds read as natural, not neon, and are visibly calmer without looking dead/desaturated.
- [ ] Skin tones remain natural under the new perceptual model (no hue shift, no chroma clipping).
- [ ] `RenderQualityJudge` still passes/auto-reduces correctly against the new renderer output (safety judge must not need special-casing for the new color path).
- [ ] No manual controls were added anywhere in the main workflow.
