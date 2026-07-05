# AI Photo Studio

Offline Android computational photography editor.

## Current build

App version inside Android project:

```text
V1.2.3
```

The project ZIP filename carries the package version. Do not upload old ZIP files into GitHub; extract the ZIP and upload the extracted project contents.

## Core rules

- Offline-first
- No generative AI
- No hallucinated pixels
- No object replacement
- No identity changes
- Original photo preserved
- Automatic editing only in the main workflow
- Non-destructive EditGraph

## Current implemented capabilities

- Android Photo Picker
- AMOLED Compose UI
- Dense Pixel Intelligence Layer
- RegionMap analysis
- SceneUnderstandingProfile
- Dynamic scene/edit explanation
- Multi-candidate adaptive editing engine
- Candidate filtering, scoring, and best-pick selection
- Better object/material candidate priority
- Before/After compare
- Conservative Auto Frame
- Full-quality Save path up to safe export size
- Share via FileProvider cache
- MediaStore saving

## Build APK with GitHub Actions

After uploading extracted project files to GitHub:

1. Open the repository.
2. Go to **Actions**.
3. Run **Build Debug APK**.
4. Download artifact:

```text
AI-Photo-Studio-debug-apk
```

Install the APK on your phone.
