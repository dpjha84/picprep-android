# PicPrep for Android

**Make every image fit.**

This is a reconstructed PicPrep 0.6.0 source release based on the PixelFit 0.1.0 Android starter project supplied in the conversation and the feature requirements recorded during the earlier development discussion. It is not the original missing 0.6.0 source archive.

## Project identity

- App name: PicPrep
- Play Store listing title: PicPrep: Image Tools
- Application ID / namespace: `com.dpjha.picprep`
- Version name/code: `0.6.0` / `6`
- Native Android: Kotlin, Jetpack Compose, Material 3
- Minimum SDK: 26
- Compile / target SDK: 36
- Java: 17

## Included functionality

- JPEG compression to a target size in KB, with quality search and dimension reduction as a fallback.
- Resize to a requested width/height, with optional aspect-ratio preservation.
- Fit within a maximum longest-side dimension.
- Export as JPEG, PNG or WebP. PNG remains lossless; JPEG transparency is composited onto white.
- Batch processing for up to 50 images selected through Android Photo Picker.
- Built-in suggested presets for job applications, online forms, ID/passport photos, email and websites.
- Custom presets saved locally using Preferences DataStore.
- Save processed files under `Pictures/PicPrep` through MediaStore.
- EXIF orientation correction and sampled decoding for large images.

## Build locally

Open the `PicPrep` folder in Android Studio with JDK 17 and Android SDK Platform 36 installed. The project uses Android Gradle Plugin 8.13.2 and Gradle 8.13. The GitHub Actions workflow installs Gradle 8.13 directly, so a Gradle wrapper is not required by that workflow.

You can also run these commands in an environment with Gradle 8.13 and Android SDK 36:

```bash
gradle clean assembleDebug assembleRelease bundleRelease
```

## GitHub Actions

Workflow: `.github/workflows/android-build.yml`

On push, pull request to `main`/`master`, or manual dispatch, it builds and uploads:

- Debug APK
- Release APK
- Release AAB

Release artifacts are unsigned unless release signing is configured. For Play Console distribution, configure a protected upload keystore using GitHub Actions secrets and add a signing configuration to the release build. Never commit the keystore or passwords.

## Important validation notes

- This reconstructed project has not been compiled or exercised on a physical Android device in this environment. Run the GitHub Actions workflow and test the resulting APK before release.
- Batch processing runs in the foreground. Leaving the app or process termination can interrupt it; this is not a WorkManager-backed resumable queue.
- PNG is lossless, so the target-KB compression setting does not reduce PNG size. The app reports whether a requested target was met rather than claiming it always can be.
- Built-in preset values are suggestions, not official requirements. Always check the receiving website or organization for its current specifications.
- AdMob and Google Play Billing are not included in this reconstructed source.
- Audit dependencies, privacy disclosures and the final Data safety form before publishing.
