# AI Photo Studio v1.4.3 — Forced-Safe Framing + Performance Optimization

Date: 2026-07-05

## Version

```text
versionCode = 143
versionName = "1.4.3"
```

App label:

```text
V1.4.3
```

Launcher/app name:

```text
AI Photo Studio v1.4.3
```

ZIP:

```text
AIPhotoStudio_v1.4.3_project.zip
```

GitHub artifact/file:

```text
AI-Photo-Studio-v1.4.3-debug-apk
AIPhotoStudio-v1.4.3-debug.apk
```

## Main fix 1 — Forced-safe framing

If a clear removable empty top band is detected and the subject is safely below it, v1.4.3 now applies the crop directly instead of letting scoring hesitate.

This targets the repeated failure where white/gray sky stayed in the image.

## Main fix 2 — Relaxed empty-band detection

The detector is less strict, so faint wires/cloud texture/compression noise should not prevent top empty-space detection as easily.

Changed logic conceptually:

```text
rowEmpty threshold relaxed
rowSubject threshold relaxed
rowTexture threshold relaxed
```

## Main fix 3 — Candidate performance optimization

To reduce lag:

- candidate scoring preview reduced from 900px to 720px
- candidate filtering now keeps up to 4 candidates instead of 5

## Expected result

For foreground-object photos with large blank bright top space:

- top crop should be much more likely
- subject should appear larger
- framing should look more intentional

If framing still does not change after this, the next step should be explicit crop diagnostics in the UI for testing.
