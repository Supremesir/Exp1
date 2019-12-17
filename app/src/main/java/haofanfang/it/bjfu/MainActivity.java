package haofanfang.it.bjfu;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;

import android.Manifest;
import android.annotation.SuppressLint;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.ImageFormat;
import android.graphics.Matrix;
import android.hardware.camera2.CameraAccessException;
import android.hardware.camera2.CameraCaptureSession;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraDevice;
import android.hardware.camera2.CameraManager;
import android.hardware.camera2.CaptureRequest;
import android.hardware.camera2.params.StreamConfigurationMap;
import android.media.Image;
import android.media.ImageReader;
import android.media.MediaScannerConnection;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.HandlerThread;
import android.provider.MediaStore;
import android.util.Log;
import android.util.Size;
import android.view.Surface;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.View;
import android.widget.EditText;
import android.widget.TextView;
import android.widget.Toast;


import com.uuzuche.lib_zxing.activity.CaptureActivity;
import com.uuzuche.lib_zxing.activity.CodeUtils;
import com.uuzuche.lib_zxing.activity.ZXingLibrary;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;

import java.nio.ByteBuffer;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;

import pub.devrel.easypermissions.AfterPermissionGranted;
import pub.devrel.easypermissions.EasyPermissions;


public class MainActivity extends AppCompatActivity implements View.OnClickListener, EasyPermissions.PermissionCallbacks {

    private static final String TAG = "Camera";

    static final SimpleDateFormat timesdf = new SimpleDateFormat("yyyy-MM-dd-HH-mm-ss", Locale.CHINA);

    static final int RC_PERMISSIONS = 1234;

    HandlerThread mBackgroundThread;
    Handler mBackgroundHandler;
    CameraManager mCameraManager;
    ImageReader mCaptureBuffer;
    CameraCaptureSession mCaptureSession;
    CaptureRequest.Builder mRequestBuilder;
    SurfaceView mSurfaceView;
    EditText root;
    TextView file;
    TextView resultText;
    File mFile;
    CameraDevice mCamera;
    Bitmap mBitmap;
    Semaphore mCameraOpenCloseLock = new Semaphore(1);


    int mOrientation;
    int mWidth;
    int mHeight;
    static int RC_ALBUM = 1;
    static int RC_QRCODE = 2;


    final SurfaceHolder.Callback mSurfaceHolderCallback = new SurfaceHolder.Callback() {

        private String mCameraId;

        private boolean ifReceptSecondCallback;


        @Override
        public void surfaceCreated(SurfaceHolder holder) {
            mCameraId = null;
            ifReceptSecondCallback = false;
        }


        @SuppressLint("MissingPermission")
        @Override
        public void surfaceChanged(SurfaceHolder holder, int format, int width, int height) {
            if (mCameraId == null) {
                String[] perms = {Manifest.permission.CAMERA};
                if (!EasyPermissions.hasPermissions(MainActivity.this, perms)) {
                    if (width == 1 && height == 1) {
                        mSurfaceView.getHolder().setFixedSize(2, 2);
                    } else if (width == 2 && height == 2) {
                        mSurfaceView.getHolder().setFixedSize(1, 1);
                    } else {
                        mSurfaceView.getHolder().setFixedSize(1, 1);
                        mWidth = width;
                        mHeight = height;
                    }
                    return;
                } else {
                    if (mHeight == 0 && mWidth == 0) {
                        mHeight = height;
                        mWidth = width;
                    }
                }
                mCameraManager = (CameraManager) getSystemService(CAMERA_SERVICE);
                try {
                    for (String cameraId : mCameraManager.getCameraIdList()) {
                        CameraCharacteristics cameraCharacteristics = mCameraManager.getCameraCharacteristics(cameraId);
                        if (cameraCharacteristics.get(cameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_FRONT) {
                            continue;
                        }
                        mOrientation = cameraCharacteristics.get(CameraCharacteristics.SENSOR_ORIENTATION);
                        StreamConfigurationMap info = cameraCharacteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);

                        Size largestSize = Collections.max(
                                Arrays.asList(info.getOutputSizes(ImageFormat.JPEG)), new CompareSizesByArea());
                        mCaptureBuffer = ImageReader.newInstance(largestSize.getWidth(),
                                largestSize.getHeight(), ImageFormat.JPEG, 2);
                        mCaptureBuffer.setOnImageAvailableListener(mImageCaptureListener, mBackgroundHandler);
                        //Compute the most optimal size
                        Size optimalSize = chooseBigEnoughSize(info.getOutputSizes(SurfaceHolder.class), mWidth, mHeight);

                        SurfaceHolder surfaceHolder = mSurfaceView.getHolder();
                        surfaceHolder.setFixedSize(optimalSize.getWidth(),
                                optimalSize.getHeight());
                        mCameraId = cameraId;
                        return;
                    }
                } catch (CameraAccessException e) {
                    e.printStackTrace();
                }
            } else if (!ifReceptSecondCallback) {
                if (mCamera != null) {
                    Log.e(TAG, "Aborting camera open because it hadn't closed");
                    return;
                }
                try {
                    mCameraOpenCloseLock.tryAcquire(2500, TimeUnit.MILLISECONDS);
                    mCameraManager.openCamera(mCameraId, mCameraStateCallback, mBackgroundHandler);
                } catch (CameraAccessException ex) {
                    Log.e(TAG, "Failed to open camera device", ex);
                } catch (InterruptedException e) {
                    throw new RuntimeException("Acquire lock of camera failed");
                }

                ifReceptSecondCallback = true;
            }
        }

        @Override
        public void surfaceDestroyed(SurfaceHolder holder) {
            holder.removeCallback(this);
        }
    };

    final CameraDevice.StateCallback mCameraStateCallback = new CameraDevice.StateCallback() {
        @Override
        public void onOpened(@NonNull CameraDevice camera) {
            Log.i(TAG, "Successfully opened camera");
            mCameraOpenCloseLock.release();
            mCamera = camera;
            try {
                List<Surface> outputs = Arrays.asList(
                        mSurfaceView.getHolder().getSurface(), mCaptureBuffer.getSurface());
                mCamera.createCaptureSession(outputs, mCameraSessionCallback, mBackgroundHandler);
            } catch (CameraAccessException ex) {
                Log.e(TAG, "Failed to create a capture session", ex);
            }
        }

        @Override
        public void onDisconnected(@NonNull CameraDevice camera) {
            Log.e(TAG, "Camera was disconnected");
            mCameraOpenCloseLock.release();
        }

        @Override
        public void onError(@NonNull CameraDevice camera, int error) {
            Log.e(TAG, "State error on device" + camera.getId() + ":code" + error);
            mCameraOpenCloseLock.release();
        }
    };

    final CameraCaptureSession.StateCallback mCameraSessionCallback = new CameraCaptureSession.StateCallback() {
        @Override
        public void onConfigured(@NonNull CameraCaptureSession session) {
            Log.i(TAG, "Finished configuring camera outputs");
            mCaptureSession = session;
            SurfaceHolder holder = mSurfaceView.getHolder();
            if (holder != null) {
                try {
                    mRequestBuilder = mCamera.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW);
                    mRequestBuilder.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE);
                    mRequestBuilder.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON_AUTO_FLASH);
                    mRequestBuilder.addTarget(holder.getSurface());
                    CaptureRequest previewRequest = mRequestBuilder.build();

                    try {
                        mCaptureSession.setRepeatingRequest(previewRequest, null, null);
                    } catch (CameraAccessException ex) {
                        Log.e(TAG, "Failed to make repeating preview request", ex);
                    }
                } catch (CameraAccessException ex) {
                    Log.e(TAG, "Failed to build preview request", ex);
                }
            } else {
                Log.e(TAG, "HOlder didn't exist when trying to formulate preview request");
            }
        }

        @Override
        public void onConfigureFailed(@NonNull CameraCaptureSession session) {

        }
    };

    static class CompareSizesByArea implements Comparator<Size> {

        @Override
        public int compare(Size o1, Size o2) {
            return Long.signum((long) o1.getHeight() * o1.getWidth() -
                    (long) o2.getWidth() * o2.getHeight());
        }
    }

    final ImageReader.OnImageAvailableListener mImageCaptureListener = new ImageReader.OnImageAvailableListener() {
        @Override
        public void onImageAvailable(ImageReader reader) {
            mBackgroundHandler.post(new ImageSaver(reader.acquireNextImage(), mFile));
        }
    };

    private class ImageSaver implements Runnable {
        private Image mImage;
        private File mFile;

        ImageSaver(Image image, File file){
            mImage = image;
            mFile = file;
        }

        @Override
        public void run() {
            ByteBuffer buffer = mImage.getPlanes()[0].getBuffer();
            byte[] jpeg = new byte[buffer.remaining()];
            buffer.get(jpeg);

            mBitmap = BitmapFactory.decodeByteArray(jpeg, 0, jpeg.length);
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            /**
             * 修复了保存的图片被逆时针旋转了90度的问题
             */
            Matrix matrix = new Matrix();
            matrix.postRotate(90);
            Bitmap bm = Bitmap.createBitmap(mBitmap, 0, 0, mBitmap.getWidth(), mBitmap.getHeight(), matrix, true);
            mBitmap = bm;

            mBitmap.compress(Bitmap.CompressFormat.JPEG, 100, baos);
            jpeg = baos.toByteArray();


            try (FileOutputStream outputStream = new FileOutputStream(mFile)) {
                outputStream.write(jpeg);
            } catch (FileNotFoundException ex) {
                Log.e(TAG, "Unable to open output file for writing", ex);
            } catch (IOException ex) {
                Log.e(TAG, "Failed to write the image to the output file", ex);
            } finally {
                mImage.close();
                String[] paths = new String[]{mFile.getAbsolutePath()};
                MediaScannerConnection.scanFile(MainActivity.this, paths, null, null);
                showToast(mFile.toString());
            }
        }
    }

    private void showToast(final String text) {
        MainActivity.this.runOnUiThread(new Runnable() {
            @Override
            public void run() {
                Toast.makeText(MainActivity.this, text, Toast.LENGTH_SHORT).show();
            }
        });
    }

    static Size chooseBigEnoughSize(Size[] choices, int width, int height) {
        List<Size> bigEnough = new ArrayList<>();
        for (Size option : choices) {
            if (option.getWidth() >= width && option.getHeight() >= height) {
                bigEnough.add(option);
            }
        }
        if (bigEnough.size() > 0) {
            return Collections.min(bigEnough, new CompareSizesByArea());
        } else {
            Log.e(TAG, "Could't find any suitable preview size");
            return choices[0];
        }
    }

    @Override
    public void onClick(View v) {
        switch (v.getId()) {
            case R.id.shot: {
                String fileTime = timesdf.format(new Date());
                String fileName = fileTime.replace(":", "") + ".jpeg";

                String rootName = root.getText().toString();



                File dir = new File(rootName);
                if (!dir.exists()) {
                    dir.mkdirs();
                }
                file.setText(fileName);
                mFile = new File(rootName, fileName);

                CaptureRequest.Builder requestBuilder = null;

                try {
                    requestBuilder = mCamera.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE);
                    requestBuilder.set(CaptureRequest.JPEG_ORIENTATION, mOrientation);
                    requestBuilder.addTarget(mCaptureBuffer.getSurface());
                } catch (CameraAccessException e) {
                    Log.e(TAG, "Create capture request error", e);
                }
                try {
                    mCaptureSession.capture(requestBuilder.build(), null, null);
                } catch (CameraAccessException e) {
                    Log.e(TAG, "Capture a image failed", e);
                }
                break;
            }
            case R.id.album: {
                Intent intent = new Intent(Intent.ACTION_PICK,
                        MediaStore.Images.Media.EXTERNAL_CONTENT_URI);
                startActivityForResult(intent, RC_ALBUM);
                break;
            }
            case R.id.scan: {
                Intent intent = new Intent(MainActivity.this, CaptureActivity.class);
                startActivityForResult(intent, RC_QRCODE);
                break;
            }
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, @Nullable Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == RC_QRCODE) {
            if (data == null) {
                return;
            }
            Bundle bundle = data.getExtras();
            if (bundle.getInt(CodeUtils.RESULT_TYPE) == CodeUtils.RESULT_SUCCESS) {
                String url = bundle.getString(CodeUtils.RESULT_STRING);
                Toast.makeText(this, "Copy the mane of this kind of plant", Toast.LENGTH_LONG).show();
                Intent intent = new Intent(Intent.ACTION_VIEW);
                intent.setData(Uri.parse(url));
                startActivity(intent);
            } else if (bundle.getInt(CodeUtils.RESULT_TYPE) == CodeUtils.RESULT_FAILED) {
                Toast.makeText(MainActivity.this, "decode the QEcode failed", Toast.LENGTH_LONG).show();
            }
        }
        if (requestCode == RC_ALBUM) {

        }
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        ZXingLibrary.initDisplayOpinion(this);
        root = findViewById(R.id.root);
        file = findViewById(R.id.file);
        resultText = findViewById(R.id.result);
        mSurfaceView = findViewById(R.id.preview);

        findViewById(R.id.album).setOnClickListener(this);
        findViewById(R.id.shot).setOnClickListener(this);
        findViewById(R.id.scan).setOnClickListener(this);

        requiresCameraAndStoragePermission();

    }

    @Override
    protected void onRestart() {
        super.onRestart();
    }

    @Override
    protected void onResume() {
        super.onResume();

        mBackgroundThread = new HandlerThread("background");
        mBackgroundThread.start();
        mBackgroundHandler = new Handler(mBackgroundThread.getLooper());

        mSurfaceView.getHolder().addCallback(mSurfaceHolderCallback);
    }

    @Override
    protected void onPause() {
        super.onPause();

        try {
            mSurfaceView.getHolder().setFixedSize(0, 0);

            mCameraOpenCloseLock.acquire();
            if (null != mCaptureSession) {
                mCaptureSession.close();
                mCaptureSession = null;
            }
            if (null != mCamera) {
                mCamera.close();
                mCamera = null;
            }
            if (null != mCaptureBuffer) {
                mCaptureBuffer.close();
                mCaptureBuffer = null;
            }
        } catch (InterruptedException e) {
            throw new RuntimeException("Interputed while trying to lock camera closing", e);
        } finally {
            mCameraOpenCloseLock.release();
        }

        if (mBackgroundHandler != null) {
            mBackgroundThread.quitSafely();
            try {
                mBackgroundThread.join();
                mBackgroundThread = null;
                mBackgroundHandler = null;
            } catch (InterruptedException ex) {
                Log.e(TAG, "Failed to stop background thread", ex);
            }
        }
    }

    @Override
    protected void onStop() {
        super.onStop();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
    }

    @AfterPermissionGranted(RC_PERMISSIONS)
    private void requiresCameraAndStoragePermission() {
        String[] perms = {
                Manifest.permission.CAMERA,
                Manifest.permission.WRITE_EXTERNAL_STORAGE,
                Manifest.permission.READ_EXTERNAL_STORAGE};
        if (!EasyPermissions.hasPermissions(this, perms)) {
            EasyPermissions.requestPermissions(this, "Request for permission", RC_PERMISSIONS, perms);
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        EasyPermissions.onRequestPermissionsResult(requestCode, permissions, grantResults, this);
    }

    @Override
    public void onPermissionsGranted(int requestCode, @NonNull List<String> perms) {
        if (requestCode == RC_PERMISSIONS) {
            Toast.makeText(this, "permissions request success", Toast.LENGTH_SHORT).show();
        }
    }

    @Override
    public void onPermissionsDenied(int requestCode, @NonNull List<String> perms) {
        if (requestCode == RC_PERMISSIONS) {
            Toast.makeText(this, "permissions request failed", Toast.LENGTH_SHORT).show();
            this.finish();
        }
    }
}
