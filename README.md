# AI Photo Studio

Offline Android computational photography editor.

## Current build

App version inside Android project:

```text
V1.4.4
```

Launcher/app name:

```text
AI Photo Studio v1.4.4
```

GitHub APK artifact/file:

```text
AI-Photo-Studio-v1.4.4-debug-apk
AIPhotoStudio-v1.4.4-debug.apk
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
