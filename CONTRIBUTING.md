# Contributing To Stream Prime

Thank you for helping improve Stream Prime. Contributions of all sizes are welcome, including bug reports, documentation fixes, UI improvements, tests, and streaming compatibility work.

## Ways To Contribute

- Fix bugs in streaming, overlays, permissions, or app UI
- Improve documentation and setup instructions
- Add tests for RootEncoder integration paths
- Report device-specific Android streaming issues
- Add overlay examples or templates
- Improve accessibility, error states, and performance

## Development Setup

1. Fork the repository.
2. Clone your fork.
3. Open the project in Android Studio.
4. Sync Gradle.
5. Run tests with:

```bash
./gradlew test
```

6. Build a debug APK with:

```bash
./gradlew assembleDebug
```

## Pull Request Guidelines

- Keep pull requests focused and easy to review.
- Explain the problem and the solution clearly.
- Include screenshots or screen recordings for UI changes.
- Add tests when changing shared streaming, encoding, or parsing behavior.
- Preserve existing RootEncoder copyright headers.
- Do not commit generated build output, local IDE files, signing keys, stream keys, tokens, or private URLs.

## Commit Style

Use clear commit messages:

```text
fix: redact RTMP endpoint logs
docs: add setup guide
feat: add overlay layer duplication
test: cover custom RTMP URL validation
```

## Reporting Bugs

Please include:

- Device model
- Android version
- App version or commit
- Streaming service or server type
- Steps to reproduce
- Expected behavior
- Actual behavior
- Relevant logs with stream keys and private URLs removed

## Code Of Conduct

By participating, you agree to follow the [Code of Conduct](CODE_OF_CONDUCT.md).
