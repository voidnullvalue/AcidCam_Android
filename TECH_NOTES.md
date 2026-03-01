# TECH_NOTES

## Frame pipeline
1. CameraX `Preview` streams to `PreviewView`.
2. CameraX `ImageAnalysis` delivers `YUV_420_888` `ImageProxy` frames.
3. Each frame is converted from `YUV_420_888` to NV21 byte layout.
4. NV21 data is wrapped in an OpenCV `Mat` and converted to RGBA (`Imgproc.COLOR_YUV2RGBA_NV21`).
5. Existing native filter pipeline is preserved by calling:
   - `AcidCam_Filter.Filter(currentSetFilter, rgbaMat.getNativeObjAddr())`
6. Processed RGBA frame is optionally flipped (`Core.flip`) and displayed through an `ImageView` overlay (`Utils.matToBitmap`).
7. Snapshot save path:
   - Processed RGBA frame -> resize -> BGR conversion -> JPEG encode -> `MediaStore` insert/write.

## Performance/tuning
- `ImageAnalysis` uses `STRATEGY_KEEP_ONLY_LATEST` to limit latency buildup.
- Analyzer runs on a single background thread executor.
- Frame buffers (`byte[]`, YUV `Mat`, RGBA `Mat`, display `Bitmap`) are reused once allocated per resolution.
- Current YUV conversion uses a safe plane copy path; if performance is insufficient on lower-end devices, optimize by:
  - adding device-specific fast-paths for contiguous UV planes,
  - minimizing per-frame allocations in conversion helpers,
  - reducing analysis resolution via CameraX target resolution/aspect settings.

## Orientation behavior
- Activity is locked to landscape to preserve the original filter composition assumptions and avoid rotation-induced pipeline breakage.

## Permission behavior
- Only runtime `CAMERA` permission is requested.
- Deny path keeps app stable: an overlay explains why permission is needed and provides a retry button.
