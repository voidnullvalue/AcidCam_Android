# TECH_NOTES

## Frame pipeline
1. CameraX `Preview` streams to `PreviewView` from `MainActivity.bindUseCases()`.
2. CameraX `ImageAnalysis` delivers `YUV_420_888` `ImageProxy` frames to `MainActivity.analyzeImage()`.
3. Each frame is converted from `YUV_420_888` to NV21 byte layout (`yuv420888ToNv21` + `copyPlane`).
4. NV21 data is wrapped in an OpenCV `Mat` and converted to RGBA (`Imgproc.COLOR_YUV2RGBA_NV21`).
5. Orientation normalization is applied in one place (`rotateForDisplay`), using `ImageProxy.getImageInfo().getRotationDegrees()` and `Core.rotate`.
6. Existing native filter pipeline is preserved by calling:
   - `AcidCam_Filter.Filter(currentSetFilter, processingBuffer.getNativeObjAddr())`
7. Processed RGBA frame is flipped based on user selection (`Core.flip`) and displayed through an `ImageView` overlay (`Utils.matToBitmap`).
8. Snapshot save path:
   - Processed RGBA frame -> resize -> BGR conversion -> JPEG encode -> `MediaStore` insert/write.

## Rotation and lifecycle handling
- No orientation lock is set in `AndroidManifest.xml`; the activity follows sensor rotation.
- Rotation uses normal activity recreation (`android:configChanges` is not added).
- Camera lifecycle safety:
  - camera starts in `onResume` via `startCamera()`
  - all use-cases unbind in `onPause`
  - OpenCV `Mat` buffers are released in `onDestroy`
- CameraX use-cases set `targetRotation` from `previewView.getDisplay().getRotation()`.
- Frame buffer sizes are recomputed in `ensureBuffers(width, height, rotationDegrees)` so portrait/landscape do not swap dimensions incorrectly.
- UI state that should survive rotation is restored via `onSaveInstanceState` (`lensFacing`, flip state, selected filter index).

## Sensor/facing assumptions
- Relies on CameraX `rotationDegrees` to represent the transform required to make frames upright for the current display rotation.
- Keeps existing filter engine behavior unchanged after orientation normalization.
- Front/back camera switching keeps previous behavior and still runs through the same normalized processing pipeline.

## UI modernization notes
- App theme is now Material 3 DayNight (`Theme.Material3.DayNight.NoActionBar`).
- A `MaterialToolbar` is used as the app bar and existing overflow/menu actions remain available.
- A `FloatingActionButton` provides a modern capture affordance while preserving existing snapshot behavior.
- Edge-to-edge is enabled with insets handling in `MainActivity.onCreate()` to avoid system bars overlapping controls.
