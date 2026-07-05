# AI Photo Studio v1.4.2 — Empty-Band Auto Frame Fix

Date: 2026-07-05

## Version

```text
versionCode = 142
versionName = "1.4.2"
```

App label:

```text
V1.4.2
```

Launcher/app name:

```text
AI Photo Studio v1.4.2
```

ZIP:

```text
AIPhotoStudio_v1.4.2_project.zip
```

GitHub artifact/file:

```text
AI-Photo-Studio-v1.4.2-debug-apk
AIPhotoStudio-v1.4.2-debug.apk
```

## Main fix

v1.4.1 still failed framing because the crop engine could reject top crops when generic subject bounds were too broad.

v1.4.2 adds a direct empty-band detector for bright/smooth/low-subject top areas.

## What changed

### 1. Top empty-band detection

The app now scans the top part of the image row-by-row to find a continuous removable band that is:

- bright
- smooth
- low texture
- low subject
- low warm-object
- low skin

This specifically targets white/gray sky, blank wall, and empty top-space failures.

### 2. More decisive crop application

If a strong empty top band is found and the subject is safely below it, crop scoring becomes less timid.

### 3. Subject safety remains

Portraits remain conservative. Object/material scenes can crop more aggressively if subject safety passes.

## Expected result

For foreground-object photos with large blank bright top space, the app should finally reduce the top area more noticeably.
