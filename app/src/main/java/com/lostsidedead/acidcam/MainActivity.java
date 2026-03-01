package com.lostsidedead.acidcam;

import android.Manifest;
import android.content.ContentValues;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.media.MediaPlayer;
import android.os.Build;
import android.os.Bundle;
import android.provider.MediaStore;
import android.view.Menu;
import android.view.MenuInflater;
import android.view.MenuItem;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.camera.core.AspectRatio;
import androidx.camera.core.CameraSelector;
import androidx.camera.core.ImageAnalysis;
import androidx.camera.core.ImageProxy;
import androidx.camera.core.Preview;
import androidx.camera.lifecycle.ProcessCameraProvider;
import androidx.camera.view.PreviewView;
import androidx.core.content.ContextCompat;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowCompat;
import androidx.core.view.WindowInsetsCompat;

import com.google.android.material.appbar.AppBarLayout;
import com.google.android.material.appbar.MaterialToolbar;
import com.google.android.material.floatingactionbutton.FloatingActionButton;
import com.google.common.util.concurrent.ListenableFuture;

import org.opencv.android.Utils;
import org.opencv.core.Core;
import org.opencv.core.CvType;
import org.opencv.core.Mat;
import org.opencv.core.MatOfByte;
import org.opencv.imgcodecs.Imgcodecs;
import org.opencv.imgproc.Imgproc;

import java.io.IOException;
import java.io.OutputStream;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MainActivity extends AppCompatActivity implements View.OnTouchListener, androidx.appcompat.widget.PopupMenu.OnMenuItemClickListener {
    private static final String STATE_CAMERA_INDEX = "cameraIndex";
    private static final String STATE_FLIP = "cameraFlip";
    private static final String CURRENT_FILTER = "current_filter";
    private static final String CURRENT_SET_FILTER = "current_set_filter";

    private static final int MENU_FILTER_MAP = 1133;
    private static final int MENU_FILTER_SORTED_MAP = 1134;

    private final AcidCam_Filter filter = new AcidCam_Filter();
    private final ActivityResultLauncher<String> cameraPermissionLauncher =
            registerForActivityResult(new ActivityResultContracts.RequestPermission(), this::onPermissionResult);

    private PreviewView previewView;
    private ImageView processedPreview;
    private LinearLayout permissionOverlay;
    private TextView permissionMessage;

    private ProcessCameraProvider cameraProvider;
    private ExecutorService analysisExecutor;

    private Mat yuvBuffer;
    private Mat rgbaBuffer;
    private Mat uprightBuffer;
    private Mat outputBgrBuffer;
    private byte[] nv21Buffer;
    private Bitmap displayBitmap;

    private int lensFacing = CameraSelector.LENS_FACING_FRONT;
    private int filterIndex = 0;
    private int currentSetFilter = 0;
    private int flipState = -1;
    private final int filterMapMax = filter.maxFilters();
    private boolean filterChanged = false;

    private int snapShotIndex = 0;
    private int takeSnapshotWait = 0;
    private boolean takeSnapshot = false;
    private boolean takeSnapshotNow = false;

    private float x1;
    private float x2;

    private MediaPlayer mp;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        System.loadLibrary("opencv_java4");
        System.loadLibrary("acidcam");

        WindowCompat.setDecorFitsSystemWindows(getWindow(), false);
        setContentView(R.layout.tutorial1_surface_view);

        View root = findViewById(R.id.root);
        AppBarLayout appBar = findViewById(R.id.app_bar);
        FloatingActionButton fabCapture = findViewById(R.id.fab_capture);
        previewView = findViewById(R.id.camera_preview);
        processedPreview = findViewById(R.id.processed_preview);
        permissionOverlay = findViewById(R.id.permission_overlay);
        permissionMessage = findViewById(R.id.permission_message);
        MaterialToolbar toolbar = findViewById(R.id.toolbar);
        setSupportActionBar(toolbar);

        ViewCompat.setOnApplyWindowInsetsListener(root, (view, windowInsets) -> {
            Insets insets = windowInsets.getInsets(WindowInsetsCompat.Type.systemBars());

            ViewGroup.MarginLayoutParams appBarLayoutParams = (ViewGroup.MarginLayoutParams) appBar.getLayoutParams();
            appBarLayoutParams.topMargin = insets.top;
            appBar.setLayoutParams(appBarLayoutParams);

            ViewGroup.MarginLayoutParams fabLayoutParams = (ViewGroup.MarginLayoutParams) fabCapture.getLayoutParams();
            fabLayoutParams.bottomMargin = insets.bottom + dpToPx(24);
            fabCapture.setLayoutParams(fabLayoutParams);
            return WindowInsetsCompat.CONSUMED;
        });

        findViewById(R.id.permission_retry).setOnClickListener(v -> requestCameraPermission());
        fabCapture.setOnClickListener(v -> showImageSavedNow());
        processedPreview.setOnTouchListener(this);

        if (savedInstanceState != null) {
            lensFacing = savedInstanceState.getInt(STATE_CAMERA_INDEX, CameraSelector.LENS_FACING_FRONT);
            flipState = savedInstanceState.getInt(STATE_FLIP, -1);
            filterIndex = savedInstanceState.getInt(CURRENT_FILTER, 0);
            currentSetFilter = savedInstanceState.getInt(CURRENT_SET_FILTER, 0);
        }

        mp = MediaPlayer.create(this, R.raw.beep);
        getOffset();

        analysisExecutor = Executors.newSingleThreadExecutor();
        requestCameraPermission();
    }

    private int dpToPx(int dp) {
        return Math.round(dp * getResources().getDisplayMetrics().density);
    }

    private void onPermissionResult(boolean granted) {
        if (granted) {
            permissionOverlay.setVisibility(View.GONE);
            startCamera();
        } else {
            permissionOverlay.setVisibility(View.VISIBLE);
            permissionMessage.setText("Camera permission was denied. Grant permission to continue.");
            Toast.makeText(this, "Camera permission is required for realtime filters.", Toast.LENGTH_LONG).show();
        }
    }

    private void requestCameraPermission() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
            onPermissionResult(true);
        } else {
            cameraPermissionLauncher.launch(Manifest.permission.CAMERA);
        }
    }

    private void startCamera() {
        ListenableFuture<ProcessCameraProvider> providerFuture = ProcessCameraProvider.getInstance(this);
        providerFuture.addListener(() -> {
            try {
                cameraProvider = providerFuture.get();
                bindUseCases();
            } catch (Exception e) {
                Toast.makeText(this, "Unable to start camera: " + e.getMessage(), Toast.LENGTH_LONG).show();
            }
        }, ContextCompat.getMainExecutor(this));
    }

    private void bindUseCases() {
        if (cameraProvider == null || previewView.getDisplay() == null) {
            return;
        }

        int rotation = previewView.getDisplay().getRotation();
        Preview preview = new Preview.Builder()
                .setTargetAspectRatio(AspectRatio.RATIO_16_9)
                .setTargetRotation(rotation)
                .build();
        preview.setSurfaceProvider(previewView.getSurfaceProvider());

        ImageAnalysis analysis = new ImageAnalysis.Builder()
                .setTargetAspectRatio(AspectRatio.RATIO_16_9)
                .setTargetRotation(rotation)
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_YUV_420_888)
                .build();

        analysis.setAnalyzer(analysisExecutor, this::analyzeImage);

        CameraSelector selector = new CameraSelector.Builder()
                .requireLensFacing(lensFacing)
                .build();

        cameraProvider.unbindAll();
        cameraProvider.bindToLifecycle(this, selector, preview, analysis);
    }

    private void analyzeImage(@NonNull ImageProxy imageProxy) {
        int width = imageProxy.getWidth();
        int height = imageProxy.getHeight();
        int rotationDegrees = imageProxy.getImageInfo().getRotationDegrees();

        ensureBuffers(width, height, rotationDegrees);
        yuv420888ToNv21(imageProxy, nv21Buffer);
        yuvBuffer.put(0, 0, nv21Buffer);

        Imgproc.cvtColor(yuvBuffer, rgbaBuffer, Imgproc.COLOR_YUV2RGBA_NV21, 4);
        Mat processingBuffer = rotateForDisplay(rgbaBuffer, rotationDegrees);

        if (filterChanged) {
            filterChanged = false;
            filter.clear_frames();
        }

        filter.Filter(currentSetFilter, processingBuffer.getNativeObjAddr());
        Core.flip(processingBuffer, processingBuffer, flipState);

        if (takeSnapshot) {
            ++takeSnapshotWait;
            if (takeSnapshotWait > 30) {
                takeSnapshotWait = 0;
                takeSnapshot = false;
                saveImage(processingBuffer);
            }
        }

        if (takeSnapshotNow) {
            saveImage(processingBuffer);
            takeSnapshotNow = false;
        }

        try {
            Utils.matToBitmap(processingBuffer, displayBitmap);
            runOnUiThread(() -> processedPreview.setImageBitmap(displayBitmap));
        } catch (Exception ignored) {
        } finally {
            imageProxy.close();
        }
    }

    private Mat rotateForDisplay(Mat source, int rotationDegrees) {
        if (rotationDegrees == 90) {
            Core.rotate(source, uprightBuffer, Core.ROTATE_90_CLOCKWISE);
            return uprightBuffer;
        } else if (rotationDegrees == 180) {
            Core.rotate(source, uprightBuffer, Core.ROTATE_180);
            return uprightBuffer;
        } else if (rotationDegrees == 270) {
            Core.rotate(source, uprightBuffer, Core.ROTATE_90_COUNTERCLOCKWISE);
            return uprightBuffer;
        }
        return source;
    }

    private void ensureBuffers(int width, int height, int rotationDegrees) {
        int requiredNv21 = width * height * 3 / 2;
        if (nv21Buffer == null || nv21Buffer.length != requiredNv21) {
            nv21Buffer = new byte[requiredNv21];
        }
        if (yuvBuffer == null || yuvBuffer.cols() != width || yuvBuffer.rows() != (height + height / 2)) {
            releaseMat(yuvBuffer);
            yuvBuffer = new Mat(height + height / 2, width, CvType.CV_8UC1);
        }
        if (rgbaBuffer == null || rgbaBuffer.cols() != width || rgbaBuffer.rows() != height) {
            releaseMat(rgbaBuffer);
            rgbaBuffer = new Mat(height, width, CvType.CV_8UC4);
        }

        int outputWidth = rotationDegrees == 90 || rotationDegrees == 270 ? height : width;
        int outputHeight = rotationDegrees == 90 || rotationDegrees == 270 ? width : height;

        if (uprightBuffer == null || uprightBuffer.cols() != outputWidth || uprightBuffer.rows() != outputHeight) {
            releaseMat(uprightBuffer);
            uprightBuffer = new Mat(outputHeight, outputWidth, CvType.CV_8UC4);
        }

        if (displayBitmap == null || displayBitmap.getWidth() != outputWidth || displayBitmap.getHeight() != outputHeight) {
            displayBitmap = Bitmap.createBitmap(outputWidth, outputHeight, Bitmap.Config.ARGB_8888);
        }
    }

    private void yuv420888ToNv21(ImageProxy image, byte[] out) {
        ImageProxy.PlaneProxy[] planes = image.getPlanes();
        int width = image.getWidth();
        int height = image.getHeight();

        int ySize = width * height;
        int uvSize = width * height / 4;

        copyPlane(planes[0], width, height, out, 0, 1);

        byte[] uBytes = new byte[uvSize];
        byte[] vBytes = new byte[uvSize];
        copyPlane(planes[1], width / 2, height / 2, uBytes, 0, 1);
        copyPlane(planes[2], width / 2, height / 2, vBytes, 0, 1);

        int outputPos = ySize;
        for (int i = 0; i < uvSize; i++) {
            out[outputPos++] = vBytes[i];
            out[outputPos++] = uBytes[i];
        }
    }

    private static void copyPlane(ImageProxy.PlaneProxy plane, int width, int height, byte[] out, int offset, int pixelStride) {
        java.nio.ByteBuffer buffer = plane.getBuffer();
        int rowStride = plane.getRowStride();
        int inputPixelStride = plane.getPixelStride();

        byte[] rowData = new byte[rowStride];
        int outputPos = offset;

        for (int row = 0; row < height; row++) {
            int bytesPerRow = Math.min(rowStride, buffer.remaining());
            buffer.get(rowData, 0, bytesPerRow);
            for (int col = 0; col < width; col++) {
                out[outputPos] = rowData[col * inputPixelStride];
                outputPos += pixelStride;
            }
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
        if (cameraProvider != null) {
            cameraProvider.unbindAll();
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
            startCamera();
        }
    }

    @Override
    protected void onDestroy() {
        if (analysisExecutor != null) {
            analysisExecutor.shutdown();
        }
        releaseMat(yuvBuffer);
        releaseMat(rgbaBuffer);
        releaseMat(uprightBuffer);
        releaseMat(outputBgrBuffer);
        if (mp != null) {
            mp.release();
        }
        super.onDestroy();
    }

    private void releaseMat(Mat mat) {
        if (mat != null) {
            mat.release();
        }
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        MenuInflater inflater = getMenuInflater();
        inflater.inflate(R.menu.main, menu);

        Menu filterMenu = menu.addSubMenu("Filters");
        for (int i = 0; i < filter.maxFilters(); ++i) {
            filterMenu.add(MENU_FILTER_MAP, i, Menu.NONE, filter.getFilterName(i));
        }

        Menu sortedMenu = menu.addSubMenu("Filters (Sorted)");
        for (int i = 0; i < filter.maxFilters(); ++i) {
            sortedMenu.add(MENU_FILTER_SORTED_MAP, i, Menu.NONE, filter.getFilterSortedName(i));
        }

        return true;
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        if (item.getGroupId() == MENU_FILTER_MAP) {
            filterIndex = item.getItemId();
            currentSetFilter = filter.getFilterIndex(filterIndex);
            filterChanged = true;
            Toast.makeText(this, "Filter changed to: " + filter.getFilterName(filterIndex), Toast.LENGTH_SHORT).show();
            return true;
        }

        if (item.getGroupId() == MENU_FILTER_SORTED_MAP) {
            filterIndex = item.getItemId();
            currentSetFilter = filter.getFilterSortedIndex(filterIndex);
            filterChanged = true;
            Toast.makeText(this, "Filter changed to: " + filter.getFilterSortedName(filterIndex), Toast.LENGTH_SHORT).show();
            return true;
        }

        int id = item.getItemId();
        if (id == R.id.takesnapshot) {
            showImageSaved();
        } else if (id == R.id.takesnapshot_now) {
            showImageSavedNow();
        } else if (id == R.id.moveleft) {
            moveLeft();
        } else if (id == R.id.moveright) {
            moveRight();
        } else if (id == R.id.fastforward) {
            filterIndex = filterMapMax - 1;
            currentSetFilter = filter.getFilterIndex(filterIndex);
            filterChanged = true;
        } else if (id == R.id.rewind_left) {
            filterIndex = 0;
            currentSetFilter = filter.getFilterIndex(filterIndex);
            filterChanged = true;
        } else if (id == R.id.flipi) {
            flipState = 0;
        } else if (id == R.id.flipy) {
            flipState = 1;
        } else if (id == R.id.flipz) {
            flipState = -1;
        } else if (id == R.id.switchcam) {
            lensFacing = lensFacing == CameraSelector.LENS_FACING_BACK
                    ? CameraSelector.LENS_FACING_FRONT
                    : CameraSelector.LENS_FACING_BACK;
            bindUseCases();
        }

        return true;
    }

    public void showImageSaved() {
        Toast.makeText(this, "Save Image: " + snapShotIndex + " in 2 seconds", Toast.LENGTH_SHORT).show();
        ++snapShotIndex;
        saveOffset();
        takeSnapshot = true;
    }

    public void showImageSavedNow() {
        Toast.makeText(this, "Save Image: " + snapShotIndex + " now", Toast.LENGTH_SHORT).show();
        ++snapShotIndex;
        saveOffset();
        takeSnapshotNow = true;
    }

    public void saveOffset() {
        SharedPreferences sp = getSharedPreferences("acidcam_prefs", MODE_PRIVATE);
        sp.edit().putInt("acidcam.key", snapShotIndex).apply();
    }

    public void getOffset() {
        SharedPreferences sp = getSharedPreferences("acidcam_prefs", MODE_PRIVATE);
        snapShotIndex = sp.getInt("acidcam.key", 0);
    }

    public void moveRight() {
        if (filterIndex < filterMapMax - 1) {
            ++filterIndex;
            currentSetFilter = filter.getFilterIndex(filterIndex);
            filterChanged = true;
        }
        Toast.makeText(this, "Filter changed to: " + filter.getFilterName(filterIndex), Toast.LENGTH_SHORT).show();
    }

    public void moveLeft() {
        if (filterIndex > 0) {
            --filterIndex;
            currentSetFilter = filter.getFilterIndex(filterIndex);
            filterChanged = true;
        }
        Toast.makeText(this, "Filter changed to: " + filter.getFilterName(filterIndex), Toast.LENGTH_SHORT).show();
    }

    @Override
    public boolean onMenuItemClick(MenuItem item) {
        return false;
    }

    @Override
    public boolean onTouch(View v, MotionEvent event) {
        return false;
    }

    @Override
    public boolean onTouchEvent(MotionEvent touchEvent) {
        switch (touchEvent.getAction()) {
            case MotionEvent.ACTION_DOWN:
                x1 = touchEvent.getX();
                return true;
            case MotionEvent.ACTION_UP:
                x2 = touchEvent.getX();
                if (x1 < x2 && Math.abs(x1 - x2) > 50) {
                    moveLeft();
                } else if (x1 > x2 && Math.abs(x2 - x1) > 50) {
                    moveRight();
                }
                return true;
            default:
                return super.onTouchEvent(touchEvent);
        }
    }

    @Override
    protected void onSaveInstanceState(@NonNull Bundle outState) {
        outState.putInt(STATE_CAMERA_INDEX, lensFacing);
        outState.putInt(STATE_FLIP, flipState);
        outState.putInt(CURRENT_FILTER, filterIndex);
        outState.putInt(CURRENT_SET_FILTER, currentSetFilter);
        super.onSaveInstanceState(outState);
    }

    public void saveImage(Mat mat) {
        mp.start();

        if (outputBgrBuffer == null || outputBgrBuffer.cols() != mat.cols() * 2 || outputBgrBuffer.rows() != mat.rows() * 2) {
            releaseMat(outputBgrBuffer);
            outputBgrBuffer = new Mat();
        }

        Mat resized = new Mat();
        org.opencv.core.Size scaleSize = new org.opencv.core.Size(mat.width() * 2, mat.height() * 2);
        Imgproc.resize(mat, resized, scaleSize, 0, 0, Imgproc.INTER_CUBIC);
        Imgproc.cvtColor(resized, outputBgrBuffer, Imgproc.COLOR_RGBA2BGR, 3);

        MatOfByte matOfByte = new MatOfByte();
        Imgcodecs.imencode(".jpg", outputBgrBuffer, matOfByte);
        byte[] bytes = matOfByte.toArray();
        matOfByte.release();
        resized.release();

        String filename = String.format("AcidCam_Image_%05d.jpg", snapShotIndex);
        ContentValues values = new ContentValues();
        values.put(MediaStore.Images.Media.DISPLAY_NAME, filename);
        values.put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg");
        values.put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/AcidCam");

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            values.put(MediaStore.Images.Media.IS_PENDING, 1);
        }

        android.net.Uri uri = getContentResolver().insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values);
        if (uri == null) {
            Toast.makeText(this, "Failed to create image record", Toast.LENGTH_SHORT).show();
            return;
        }

        try (OutputStream outputStream = getContentResolver().openOutputStream(uri)) {
            if (outputStream == null) {
                throw new IOException("Unable to open output stream");
            }
            outputStream.write(bytes);
        } catch (IOException e) {
            Toast.makeText(this, "Image save failed: " + e.getMessage(), Toast.LENGTH_SHORT).show();
            getContentResolver().delete(uri, null, null);
            return;
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ContentValues pending = new ContentValues();
            pending.put(MediaStore.Images.Media.IS_PENDING, 0);
            getContentResolver().update(uri, pending, null, null);
        }
    }
}
