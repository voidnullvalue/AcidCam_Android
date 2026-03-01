# Building AcidCam_Android

## Toolchain
- **Android Studio:** Koala (2024.1.1) or newer.
- **JDK:** 17 (bundled with recent Android Studio is supported).
- **Android SDK:** API 35 (compile/target SDK 35).
- **Android NDK:** 26.3.11579264 (configured in Gradle for app + OpenCV modules).
- **Gradle / AGP:** Gradle 8.7, Android Gradle Plugin 8.5.2.

## Steps
1. Clone the repository.
2. Open the project root in Android Studio.
3. Allow Gradle sync to complete.
4. If prompted, install missing SDK/NDK packages:
   - Android SDK Platform 35
   - Android SDK Build-Tools (latest)
   - NDK 26.3.11579264
   - CMake (from SDK Manager)
5. Build from terminal or IDE:
   - `./gradlew :app:assembleDebug`
6. Run on an **arm64-v8a** emulator/device (recommended) with camera support.
7. On first launch, grant Camera permission.

## Notes
- minSdk 21 is required due to Android NDK r26+ native build support constraints.
- `local.properties` should remain local and is not required in version control.
- Image saves are handled with `MediaStore` under `Pictures/AcidCam` and do not require legacy storage permissions.
