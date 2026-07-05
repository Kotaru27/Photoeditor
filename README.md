# AI Photo Studio

Offline Android computational photography editor.

## Current build

App version inside Android project:

```text
V1.4.5
```

Launcher/app name:

```text
AI Photo Studio v1.4.5
```

GitHub APK artifact/file (name is generated automatically from `versionName` by the build workflow):

```text
AI-Photo-Studio-v1.4.5-debug-apk
AIPhotoStudio-v1.4.5-debug.apk
```

The project ZIP filename carries the package version. Do not upload old ZIP files into GitHub; extract the ZIP and upload the extracted project contents.

## Current implemented capabilities

- Android Photo Picker
- AMOLED Compose UI
- Dense Pixel Intelligence Layer
- RegionMap analysis
- SceneUnderstandingProfile
- Dynamic scene/edit explanation (now mentions framing when Auto Frame crops)
- Multi-candidate adaptive editing engine
- Candidate filtering, scoring, and best-pick selection
- Auto Frame 2.4: relaxed empty-band detection, stricter object-scene subject bounds, headroom-aware crop boost, hardened portrait top-crop cap
- **Compose-first render order (v1.4.5): crop now applies before tone/color/local-light shaping, so local masks are computed against the final composed frame — see `V1_4_5_COMPOSE_FIRST_RENDER_ORDER_FIX.md`**
- Candidate preview performance optimization
- Mask-based local grading renderer (reduced flat RGB sky subtraction)
- Live staged transformation preview
- Before/After compare
- Full-quality Save path up to safe export size
- Share via FileProvider cache
- MediaStore saving

## Planned next (v1.5.0 direction)

See `V1_5_0_ROADMAP_PERCEPTUAL_COLOR_AND_DETAIL.md` for the larger engine work:
perceptual color grading, base/detail separation for object enhancement, and a
dedicated background distraction reducer.

**Do not start v1.5.0 until Auto Frame is confirmed working on real test photos.**
The current priority is verifying that v1.4.4/v1.4.5's cropping behavior actually
produces a good result on the phone, per the project's one-feature-at-a-time rule.

