# AI Photo Studio — Master Direction, Failure Conditions & Solutions

Date: 2026-07-05
Current checkpoint after v1.0.7 discussion

This document consolidates the product direction, technical principles, known failure conditions, and proposed solutions before the next major implementation update.

---

## 1. Core Product Goal

AI Photo Studio must become an offline automatic professional photo editor that makes an existing real photograph look professionally edited while preserving authenticity.

The target is not:

- a gallery app
- a manual Lightroom clone
- a generic filter app
- a beauty filter app
- a generative AI app
- an image replacement/manipulation tool

The target is:

> Same real photo, automatically transformed through professional computational editing, like a senior editor worked on it.

---

## 2. Iron Rules

### Never allowed

- No generative AI
- No hallucinated detail
- No object replacement
- No identity change
- No fake generated texture
- No manual editing burden in the main workflow
- No fixed one-size-fits-all presets
- No destructive overwrite of original photo

### Always required

- Offline-first behavior
- Original photo preserved
- Non-destructive EditGraph
- Real image-processing only
- Automatic professional edit
- Before/After compare
- Full-quality export from original image
- Save/share without damaging source

---

## 3. Correct Meaning of “Editing” and “Color Grading”

Basic correction is not enough.

Basic correction includes:

- exposure
- contrast
- temperature
- saturation
- vibrance
- highlights/shadows

These are only the foundation.

The app must eventually perform automatic **non-linear professional editing and grading**, including:

- tone curves
- S-curves
- highlight shoulder/rolloff
- shadow toe shaping
- midtone shaping
- channel curves
- color curves
- HSL-style color family control
- luminance masks
- local dodge and burn
- color harmony
- shadow/midtone/highlight color balance
- subject/background separation
- local detail/texture control
- local color grading
- composition/framing improvement

The goal is not simply to make the image warmer, brighter, or more saturated.

The goal is:

> image-specific professional visual transformation.

---

## 4. Correct High-Level User Workflow

The user should not need editing knowledge.

Main flow:

```text
Open Photo
→ App analyzes deeply
→ App tests multiple professional edit patterns
→ App chooses best edit
→ App shows final transformation
→ User compares Before/After
→ User saves or shares
```

No manual sliders in the main workflow.

---

## 5. Desired Premium Processing Experience

Instant edits are fast but not trustworthy if shallow.

The app may take a few seconds if the result is better.

Desired progress flow:

```text
Analyzing image...
Understanding subject...
Reading light and color...
Testing professional edits...
Choosing best result...
Finalizing...
```

If possible, the app should show a live/magic preview:

- original appears
- light correction fades in
- subject shaping fades in
- color grade fades in
- final polish appears

This is not manual editing. It is visual feedback while the app edits automatically.

---

## 6. Frozen Architecture

Architecture remains:

```text
Image
→ Visual Intelligence Analyzer
→ Professional Editing Planner
→ Editing Strategy Engine
→ RenderGraphExecutor
→ Calibration Laboratory
→ ExportEngine
→ MediaStore
```

Do not replace this architecture.

The next major engine should be implemented inside this architecture.

---

## 7. Current App Level — Honest Assessment

Current app is an early offline automatic enhancer prototype.

It already has:

- Android Photo Picker
- AMOLED Compose UI
- offline analysis
- EditGraph
- RenderGraphExecutor
- Before/After
- Save/share
- basic automatic editing
- quality judge
- versioned ZIPs

But it is not yet a senior editor replacement.

Main weaknesses:

- shallow image understanding
- unreliable subject/person/object classification
- too much recipe/category dependence
- only one edit candidate generated
- limited local masks
- limited color grading
- quality judge is mostly technical, not aesthetic
- Auto Frame is basic
- no true straightening
- no candidate scoring/best-pick system

---

## 8. Correct Future Engine Direction

Next major target:

```text
v1.1.0 Multi-Candidate Adaptive Grading Engine
```

Core idea:

```text
Analyze deeply
→ build region maps
→ generate multiple edit candidates
→ render candidates at preview scale
→ score candidates
→ pick best automatically
→ render final preview/export
```

The user does not pick the candidate.

The app picks the best edit.

---

## 9. Required Future Components

### 9.1 RegionMap

Divide the image into a grid, for example 12x12 or 16x16.

For each region/cell measure:

- luminance
- contrast
- saturation
- hue/warmth
- clipping
- texture
- edge density
- noise
- sky likelihood
- skin-like likelihood
- green likelihood
- warm-object likelihood
- background distraction
- saliency/attention

Purpose:

> Understand every major part of the image, not only global averages.

---

### 9.2 Saliency / Subject Map

Estimate likely subject without generative AI using:

- center bias
- detail density
- edge density
- contrast separation
- color separation
- foreground position
- local brightness contrast
- shape/detail concentration
- human/skin safety hints

Purpose:

> Find what should attract the viewer’s eye.

---

### 9.3 Distraction Map

Identify areas that steal attention:

- bright edges
- saturated background
- high-contrast background details
- large white sky
- neon greens
- edge objects
- clutter near borders

Purpose:

> Decide what should become quieter.

---

### 9.4 ToneCurvePlanner

Plan non-linear tone shaping:

- shadows
- blacks
- lower midtones
- upper midtones
- highlights
- whites
- S-curve strength
- highlight rolloff
- shadow toe
- subject midtone lift
- sky compression

Purpose:

> Move beyond simple exposure/contrast sliders.

---

### 9.5 ColorGradePlanner

Plan real grading:

- channel curve bias
- warm/cool tonal balance
- shadow/midtone/highlight color balance
- HSL color family control
- green control
- sky color control
- skin protection
- warm object protection
- color harmony
- saturation compression

Purpose:

> Create adaptive image-specific color style, not generic filters.

---

### 9.6 CandidateGenerator

Generate several possible EditGraphs internally.

Examples:

- Clean Natural Correction
- Subject Depth Polish
- Soft Portrait Safe
- Rich Object/Product Polish
- Atmospheric Landscape
- Low-Light Rescue
- Muted Background Focus
- Cinematic Natural Depth
- High-Key Clean
- Color Harmony Grade

These must be adaptive candidates, not fixed presets.

---

### 9.7 CandidateRenderer

Render candidate previews at reduced size for scoring.

Purpose:

> Let the app compare multiple edits without waiting too long.

---

### 9.8 CandidateScorer

Score each candidate using technical and aesthetic criteria:

- subject improvement
- background distraction reduction
- highlight safety
- shadow detail
- skin safety
- color harmony
- naturalness
- edit strength
- first-glance appeal
- no fake HDR
- no over-saturation
- no over-yellowing
- no hot skin
- no crushed shadows
- no blown highlights
- no halos
- no excessive vignette

---

### 9.9 BestEditSelector

Pick the best candidate automatically.

Purpose:

> Avoid relying on a single guessed recipe.

---

### 9.10 LiveProgressPreview

Optional but desirable.

Show user that the app is working:

- analyzing
- testing edits
- choosing best
- applying final polish

Possible visual staged preview:

- original
- light shaping
- local subject emphasis
- color grade
- final polish

---

## 10. Known Failure Conditions and Solutions

### Failure 1 — Warm object mistaken as portrait

Example:

- brass idol/object classified as portrait

Cause:

- warm/brown/brass tones look similar to skin in simple pixel logic

Solution:

- separate `portraitSafetyLikelihood` from `ornateObjectLikelihood`
- use portrait safety only when human/skin confidence is strong and ornate-object/detail confidence is low
- do not treat warm color alone as portrait

---

### Failure 2 — Person treated as object/product

Example:

- child/person receives aggressive object polish
- face becomes too bright/warm

Cause:

- generic hero-object logic overpowers human safety

Solution:

- skin/person safety should reduce subject lift, local contrast, clarity, and warm richness
- hot skin highlight check in quality judge
- portrait route should protect skin but not over-soften identity

---

### Failure 3 — Instant edit but shallow result

Cause:

- only one edit candidate generated
- limited analysis

Solution:

- implement multi-candidate generation and scoring
- accept 2–5 seconds processing time for better results

---

### Failure 4 — Image looks like only brightness/contrast/saturation changed

Cause:

- global corrections dominate
- weak local masks
- no real curves/HSL/channel grading

Solution:

- add non-linear tone curves
- add color curves/channel curves
- add HSL-like color family controls
- add local subject/background/sky/skin/object masks

---

### Failure 5 — Wrong recipe causes wrong edit

Cause:

- category-based editing

Solution:

- recipes should be hints/labels only
- visual goals should drive actual edit parameters
- multi-candidate scorer should pick best candidate

---

### Failure 6 — Over-bright skin or hot faces

Cause:

- subject lift/highlight boost affects skin too strongly

Solution:

- skin-hot pressure checks
- reduce local lift on skin-like regions
- compress hot skin highlights
- avoid excessive warmth/saturation on skin

---

### Failure 7 — Brass/warm objects become too yellow

Cause:

- hero color richness too aggressive

Solution:

- warm-object richness should increase depth and separation, not only yellow saturation
- use luminance/depth contrast and selective saturation compression
- avoid pushing red/yellow channels globally

---

### Failure 8 — Sky glow or patchy highlight artifacts

Cause:

- local sky/highlight recovery too uneven
- vignette/center focus affecting sky incorrectly

Solution:

- sky mask should be smoother
- avoid strong vignette in sky zones
- use highlight rolloff/curve instead of local dark patches

---

### Failure 9 — Background remains distracting

Cause:

- background calm is too weak or only position-based

Solution:

- use distraction map
- reduce background saturation/contrast/brightness selectively
- avoid crushing shadows
- protect subject from background edits

---

### Failure 10 — Auto Frame crops unfairly or compare jumps

Cause:

- After cropped but Before not cropped

Solution already applied:

- Before preview should use same geometry crop as After

Future solution:

- Auto Frame confidence scoring
- avoid crop when confidence is low
- protect faces/heads/main subject

---

### Failure 11 — Full export lower quality than original

Cause:

- saving preview bitmap instead of re-rendering from original

Solution already applied:

- Save/Share decode larger export bitmap and render from original URI

Future solution:

- tile-based full-resolution rendering
- metadata handling

---

### Failure 12 — Export crash/memory pressure

Cause:

- large bitmap rendering

Solution:

- cap export dimension for now, currently safer social size
- disable repeated Save/Share while busy
- future tile renderer

---

### Failure 13 — Share creates duplicate gallery files

Cause:

- share path saved to MediaStore

Solution already applied:

- Share uses temporary FileProvider cache
- Save uses MediaStore

---

### Failure 14 — Save/Share button spam

Cause:

- no busy state

Solution already applied:

- disable buttons during Save/Share

---

### Failure 15 — Build version/install confusion

Cause:

- versionCode not increasing

Solution already applied:

- versionCode should increase each release

---

### Failure 16 — GitHub build compile error due to missing function

Example:

- `renderOriginalFrame` unresolved

Solution already applied:

- add public helper in RenderGraphExecutor

---

### Failure 17 — Debug-like UI

Cause:

- exposing technical labels/stats to normal user

Solution:

- hide technical metrics
- show simple status
- keep detailed debug info internal or optional later

---

### Failure 18 — Large-font UI crowding

Cause:

- fixed bottom panel layout

Solution:

- adaptive/scrollable bottom sheet later
- shorter labels now

---

### Failure 19 — No true straightening

Cause:

- Auto Frame exists, but not line/horizon correction

Solution:

- future horizon/line detection
- conservative auto-straighten only when confident

---

### Failure 20 — Metadata not preserved

Cause:

- JPEG export does not copy EXIF

Solution:

- future metadata policy
- preserve safe metadata
- optionally remove location for privacy

---

## 11. Practical Next Implementation Plan

### Next major version: v1.1.0

Implement:

```text
Multi-Candidate Adaptive Grading Engine
```

Suggested order:

1. Add `RegionMap` data model
2. Add region-grid analyzer
3. Add saliency/distraction scoring
4. Add candidate EditGraph generator
5. Add reduced-size candidate renderer
6. Add candidate scorer
7. Pick best candidate automatically
8. Add staged progress UI
9. Keep Save/Share full-quality path
10. Keep current no-manual-slider workflow

---

## 12. What Not To Do Next

Do not keep adding one-off fixes for individual photos.

Avoid:

```text
if brass do this
if child do this
if sky do this
if food do this
```

Instead build:

```text
region understanding
visual goals
candidate generation
candidate scoring
best-pick selection
```

That is the scalable path.

---

## 13. Final Product Philosophy

The app should feel like it is thinking.

Not:

```text
instant brightness boost
```

But:

```text
analyzing image
understanding subject
trying professional edits
choosing the best
revealing final result
```

The highest compliment remains:

> “I can’t tell AI edited this. It just looks professionally edited.”

---

# 14. v1.1.0 Update — Multi-Candidate Engine Status

Date added: 2026-07-05

## 14.1 What v1.1.0 implemented

The app now has the first version of a multi-candidate adaptive editing engine.

New component:

```text
MultiCandidateAdaptiveEditingEngine
```

Current flow:

```text
Image
→ Visual Intelligence Analyzer
→ Base Professional Editing Planner
→ Multi-Candidate Adaptive Editing Engine
→ Candidate preview rendering
→ Candidate scoring
→ Best candidate selection
→ RenderGraphExecutor
→ Quality Judge
→ Preview / Export
```

This keeps the frozen architecture intact while adding deeper behavior inside the planner/strategy/render stages.

---

## 14.2 Internal candidates currently generated

v1.1.0 currently generates and tests these candidate edit patterns:

1. Clean natural grade
2. Subject depth polish
3. Soft human-safe grade
4. Rich object detail grade
5. Atmospheric sky and depth grade
6. Muted background color harmony
7. Low-light natural rescue when needed

Important:

These are not user-facing filters.

They are internal adaptive edit candidates. The user does not choose them.

The app renders small previews internally, scores them, and selects the best candidate automatically.

---

## 14.3 Current candidate scoring checks

The first candidate scorer checks:

- edit strength
- highlight clipping
- shadow clipping
- skin-hot pressure
- top/sky hot highlight pressure
- saturation risk
- edge/vignette darkness
- center/background separation
- background saturation reduction
- portrait safety likelihood
- ornate/warm object likelihood
- sky likelihood
- background distraction
- low-light/noise pressure

This is a first scoring system, not final taste intelligence.

---

## 14.4 Why this is better than v1.0.x

Previous versions usually produced one edit based on one guessed route.

That caused failures such as:

```text
warm object → portrait polish
person → object polish
landscape → object polish
```

v1.1.0 reduces that risk because it tries multiple possible interpretations and picks the safest/best candidate.

It is now closer to:

```text
try several professional edits → judge → choose best
```

instead of:

```text
guess one category → apply one edit
```

---

## 14.5 What v1.1.0 still does NOT fully solve

v1.1.0 is a first implementation. It is not yet the final senior-editor engine.

Still missing:

### Full RegionMap

The app does not yet build a full 12x12 or 16x16 region map data structure.

Needed:

- per-cell luminance
- per-cell contrast
- per-cell saturation
- per-cell warmth
- per-cell texture
- per-cell clipping
- per-cell saliency
- per-cell distraction
- per-cell sky/skin/object/green likelihood

### Real saliency map

Current subject detection is still heuristic.

Needed:

- stronger subject likelihood map
- better center/foreground/detail/color separation logic
- better attention/distraction map

### True color grading operations

Current renderer still does not have enough advanced grading operations.

Needed:

- tone curve control by zone
- RGB/channel curves
- HSL-like color family control
- green control
- skin tone line protection
- sky color control
- shadow/midtone/highlight color balance

### Better local masks

Current local masks are still rough.

Needed:

- subject mask
- background mask
- sky mask
- skin/warm tone mask
- green mask
- texture mask
- highlight mask
- shadow mask
- edge distraction mask

### Better candidate scoring

Current scoring is technical and early aesthetic.

Needed:

- stronger first-glance appeal scoring
- stronger naturalness scoring
- stronger subject-background hierarchy scoring
- stronger color harmony scoring
- better penalty for fake-looking edits

### Live progress preview

v1.1.0 text says:

```text
Analyzing and testing edits...
```

but it does not yet show staged visual transformations.

Future desired stages:

- analyzing
- light shaping
- subject shaping
- color grading
- background calming
- final polish

### True Auto Straighten

Auto Frame exists, but real straighten/horizon correction is still not implemented.

### Metadata policy

EXIF/metadata preservation or privacy stripping is still not implemented.

---

## 14.6 Updated failure conditions after v1.1.0

### Failure A — Candidate scorer picks technically safe but visually weak edit

Cause:

- scorer may over-prioritize safety
- aesthetic scoring is still weak

Solution:

- improve scoring for first-glance appeal
- reward subject separation and background calming more
- penalize edits that are too weak

---

### Failure B — Candidate scorer picks visually strong but unnatural edit

Cause:

- candidate may improve contrast/color but damage realism

Solution:

- stronger naturalness score
- stronger penalties for hot skin, neon greens, over-yellow objects, fake HDR, halos

---

### Failure C — Wrong subject understanding still affects candidates

Cause:

- no full RegionMap or saliency map yet

Solution:

- implement RegionMap
- implement saliency/distraction maps
- make candidates based on maps rather than only global analysis

---

### Failure D — Color grading still too basic

Cause:

- renderer lacks full curve/channel/HSL operations

Solution:

- expand EditGraph with curve and color grade operations
- implement RGB/channel curves
- implement HSL-like color-family adjustments

---

### Failure E — Processing may become slower

Cause:

- multiple candidates require multiple preview renders

Solution:

- render candidates at small scoring size only
- cache analysis maps
- limit candidates based on image signals
- show better progress feedback

---

## 14.7 Updated next implementation priorities

### Priority 1 — Build RegionMap

Create a real region grid analysis structure.

This is the foundation for understanding every inch/corner.

### Priority 2 — Saliency and Distraction Maps

Use RegionMap to estimate:

- likely subject regions
- distracting regions
- background regions
- sky/highlight regions
- texture/object regions

### Priority 3 — Expand EditGraph for grading

Add non-linear grading operations:

- tone curve points or curve strength zones
- RGB/channel curve controls
- color family adjustments
- shadow/midtone/highlight color balance

### Priority 4 — Improve CandidateGenerator

Generate candidates from visual goals, not just modified base graph.

### Priority 5 — Improve CandidateScorer

Add scoring for:

- first-glance appeal
- subject hierarchy
- color harmony
- local naturalness
- edit confidence

### Priority 6 — Live staged preview

Add visual progress stages so the user feels the app is actively editing.

---

## 14.8 Updated product direction summary

The app should evolve from:

```text
automatic enhancer
```

toward:

```text
offline multi-candidate computational photography editor
```

The correct final behavior:

```text
Analyze image deeply
→ understand regions/subject/background
→ generate multiple adaptive grades
→ score candidates
→ choose best automatically
→ show staged transformation
→ export high-quality final image
```

Still no generative AI.
Still no hallucination.
Still no manual editing burden.

---

# 15. Scene Understanding Requirements

Date added: 2026-07-05

## 15.1 Important clarification

The app should not merely label the image as:

```text
portrait
landscape
object
food
sky
```

That is too shallow and can lead to wrong edit choices.

The app must build a deeper understanding of the entire image situation:

```text
scene + subject + background + lighting + color mood + composition + risks + opportunities
```

The goal is not to name objects like a generative AI model.

The goal is to understand enough about the real image to edit it correctly.

---

## 15.2 Dense pixel-level analysis

The app should analyze a downscaled analysis bitmap at dense pixel level.

Required dense maps:

- luminance
- saturation/chroma
- warmth/hue bias
- edge/detail
- texture
- smoothness
- highlight
- shadow
- skin likelihood
- sky likelihood
- green likelihood
- warm-object/material likelihood
- saliency/attention
- distraction

Solution:

```text
Use DenseAnalysisMap as the base intelligence layer.
```

---

## 15.3 Region-level scene structure

The app should understand spatial regions:

- top
- bottom
- center
- edges
- corners
- foreground
- background
- subject area
- sky area
- shadow area
- highlight area
- texture area
- distraction area

Solution:

```text
Use RegionMap + DenseAnalysisMap together.
```

---

## 15.4 Subject understanding

The app should estimate:

- likely subject position
- subject strength
- whether subject is detailed or smooth
- whether subject is too dark/bright
- whether subject is separated from background
- whether subject needs lift, detail, softness, or protection

Solution:

```text
Use saliency, center/foreground bias, edge/detail density, contrast separation, color separation, and local brightness.
```

---

## 15.5 Background understanding

The app should estimate:

- background distraction
- background brightness
- background saturation
- clutter/line/edge pressure
- whether background should be calmed
- whether background atmosphere should be preserved

Solution:

```text
Use distraction maps, edge-zone analysis, saturation maps, texture maps, and subject-background separation.
```

---

## 15.6 SceneUnderstandingProfile

The app should produce a structured scene profile.

It should estimate weighted properties such as:

- indoor likelihood
- outdoor likelihood
- sky-heavy scene
- greenery/nature-heavy scene
- object/product-like scene
- person/portrait-like scene
- food/warm material-like scene
- low-light scene
- high-key bright scene
- backlit subject
- flat/dull lighting
- dramatic contrast scene
- cluttered background
- foreground object scene
- social/post-worthy composition risk/opportunity

Important:

This profile should not force a fixed preset.

It should guide candidate generation, candidate filtering, scoring, and local protections.

---

## 15.7 Image contents understanding

The app should understand likely content regions at a practical non-generative level:

- person/skin-like region
- object/material region
- sky region
- greenery region
- building/structure-like region
- food/warm material-like region
- water/blue/cool region if possible
- text/document-like region if possible
- pet/animal-like region later if offline models are added

Solution:

```text
Use non-generative computer vision features: color maps, texture maps, edge maps, smoothness maps, saliency maps, connected-region behavior, and optional future offline detectors.
```

---

## 15.8 Lighting understanding

The app should understand:

- overexposed sky/highlights
- underexposed subject
- harsh light
- soft light
- low-light/noisy scene
- flat lighting
- backlit scene
- strong dynamic range
- dull midtones

Solution:

```text
Use luminance distribution, highlight/shadow maps, top/bottom brightness, subject/background brightness difference, and local contrast.
```

---

## 15.9 Color mood understanding

The app should understand:

- warm mood
- cool mood
- green-heavy scene
- washed-out colors
- over-saturated colors
- bad color cast
- skin/warm tone risk
- brass/gold/warm object richness opportunity
- sky color opportunity
- background color distraction

Solution:

```text
Use hue/warmth maps, saturation maps, color-family likelihoods, skin/object/green/sky maps.
```

---

## 15.10 Composition understanding

The app should understand:

- subject placement
- empty top/side space
- distracting edges
- subject too low/high
- strong lines/railings
- clutter around subject
- whether a conservative crop improves image
- whether original framing should be preserved

Solution:

```text
Use saliency map, distraction map, edge analysis, empty-space pressure, and Auto Frame confidence.
```

---

## 15.11 Candidate edit suitability

The app should not generate candidates blindly.

For each candidate, it should know:

- why this candidate might fit
- what image problem it solves
- what risks it has
- whether it matches the scene profile
- whether it improves subject/background relationship

Solution:

```text
Use SceneUnderstandingProfile to filter and weight candidate edits before rendering/scoring.
```

---

## 15.12 Multi-candidate best-pick system based on scene understanding

Candidate generation should depend on the scene profile.

Examples:

### Foreground object scene

Generate:

- clean natural
- subject depth
- rich object detail
- muted background
- sky recovery if needed

Avoid:

- soft human-safe unless portrait safety is genuinely high

### Portrait-like scene

Generate:

- clean natural
- soft human-safe
- subject depth with skin protection
- muted background

Avoid:

- aggressive object/material detail

### Sky/landscape scene

Generate:

- atmospheric sky
- foreground depth
- natural color depth
- clean natural

Avoid:

- portrait-safe or object-heavy candidates unless strong foreground subject exists

---

## 15.13 Avoid shallow labels

The app should not internally think:

```text
this is portrait, apply portrait preset
```

It should think:

```text
subject is likely foreground warm detailed object
background is green and distracting
top sky is bright
foreground has important texture
skin-like hand region exists but is not dominant
best edit should enrich object, calm background, recover sky, protect hand
```

That is the desired level of image understanding.

---

## 15.14 Updated next implementation direction

Next recommended implementation:

```text
v1.2.1 SceneUnderstandingProfile + Candidate Filtering
```

Goals:

1. Build SceneUnderstandingProfile from DenseAnalysisMap + RegionMap + global analysis.
2. Estimate scene, subject, background, lighting, color mood, composition, risks, and opportunities.
3. Filter irrelevant candidates before rendering.
4. Prevent human-safe candidate on object/material images.
5. Prevent aggressive object candidate on true portraits.
6. Boost candidates that match the scene profile.
7. Improve candidate scoring using scene needs.
8. Reduce lag by not testing irrelevant candidates.

---

## 15.15 Updated principle

The app should understand:

```text
what the image situation is
```

not merely:

```text
what category the image belongs to
```

This is required for reliable automatic editing across many image types.
