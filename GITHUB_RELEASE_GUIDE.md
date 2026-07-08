# GitHub Open-Source Release Guide

Use this guide to publish Stream Prime professionally on GitHub.

## 1. Create The Repository

Recommended repository name:

```text
stream-prime
```

Recommended description:

```text
Free open-source Android live streaming app with RTMP broadcasting, custom overlays, screen capture, file streaming, and RootEncoder-powered mobile encoding.
```

Visibility: Public.

License: Apache License 2.0.

Do not initialize the GitHub repository with a README, license, or `.gitignore` if you are pushing this prepared local project.

## 2. Add Topics

Recommended GitHub topics:

```text
android
kotlin
live-streaming
mobile-live-streaming
rtmp
rtmp-streaming
android-rtmp-broadcaster
rootencoder
streaming-overlay
custom-overlay
screen-recording
mobile-broadcasting
open-source-live-streaming
```

## 3. Initialize And Push

From the project root:

```bash
git init
git branch -M main
git add .
git status
git commit -m "chore: prepare open-source release"
git remote add origin https://github.com/Saswata-Codes/stream-prime.git
git push -u origin main
```

If the remote already exists:

```bash
git remote set-url origin https://github.com/Saswata-Codes/stream-prime.git
git push -u origin main
```

## 4. Add Screenshots

Create screenshots on a real Android device or emulator:

- Home screen
- Stream Settings
- Overlay Editor
- Live preview/stream state
- File streaming screen

Save them under:

```text
media/screenshots/
```

Suggested names:

```text
home.png
settings.png
overlay-editor.png
live-stream.png
file-streaming.png
```

Then update the screenshots table in `README.md`.

## 5. Create The First Release

Recommended first tag:

```text
v1.0.0
```

Build before releasing:

```bash
./gradlew test assembleDebug
```

For a public APK release, configure release signing locally or in GitHub Actions secrets, then build:

```bash
./gradlew assembleRelease
```

Create a release on GitHub:

1. Go to Releases.
2. Click "Draft a new release".
3. Choose or create tag `v1.0.0`.
4. Title it `Stream Prime v1.0.0`.
5. Attach APK artifacts only if they are properly signed and intended for public download.

## 6. Good Release Notes Template

```markdown
## Stream Prime v1.0.0

First public open-source release of Stream Prime.

### Highlights
- RTMP live streaming from Android
- Screen capture and camera streaming
- Custom overlay editor and overlay layer support
- File streaming support
- RootEncoder-powered encoding and streaming modules

### Developer Notes
- Built with Kotlin, Java, AndroidX, Gradle Kotlin DSL, and RootEncoder modules.
- Requires Android SDK 36 and JDK 17.

### Known Limitations
- Screenshots and demo video are still being expanded.
- Device-specific streaming behavior may vary by Android version and vendor.

### Credits
Special thanks to RootEncoder and its contributors.
```

## 7. Invite Contributors

- Add `good first issue` labels for small tasks.
- Add `help wanted` for areas where community support is welcome.
- Keep issue templates clear and friendly.
- Respond to first-time contributors with specific review notes.
- Create a short project board for bugs, documentation, and roadmap tasks.

## 8. Maintain The Project

- Keep dependencies updated through Dependabot.
- Run CI on pull requests.
- Review Android permission changes when target SDK changes.
- Keep screenshots current after UI changes.
- Rotate any leaked stream keys immediately.
- Never commit signing keys, private URLs, API keys, or local machine paths.
- Tag releases consistently with semantic versioning.
