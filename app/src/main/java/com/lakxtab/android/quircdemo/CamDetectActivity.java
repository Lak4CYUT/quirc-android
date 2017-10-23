package com.lakxtab.android.quircdemo;

import android.Manifest;
import android.app.Activity;
import android.content.Context;
import android.content.pm.PackageManager;
import android.graphics.ImageFormat;
import android.graphics.SurfaceTexture;
import android.hardware.camera2.CameraAccessException;
import android.hardware.camera2.CameraCaptureSession;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraDevice;
import android.hardware.camera2.CaptureRequest;
import android.hardware.camera2.params.StreamConfigurationMap;
import android.media.Image;
import android.media.ImageReader;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.HandlerThread;
import android.support.annotation.NonNull;
import android.util.Log;
import android.util.Size;
import android.view.Surface;
import android.view.TextureView;
import android.widget.TextView;
import android.widget.Toast;

import java.io.UnsupportedEncodingException;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.Iterator;
import java.util.List;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;

public class CamDetectActivity extends Activity
{
    private static final String TAG = "QrDetectActivity";

    private static final int REQ_PERMISSION_CAMERA = 1;

    private android.hardware.camera2.CameraManager mCamMgr;
    private HandlerThread mBgThread;
    private HandlerThread mBgDcThread;
    //private Handler mFgHandler;
    private Handler mBgHandler;
    private Handler mBgDcHandler;

    private TextureView mTextureView;
    private TextView mTxtQrResult;
    private CameraDevice mCamDev;
    private String mCameraId;
    private Size mPreviewSize;
    private ImageReader mImageReader;
    private CameraCaptureSession mCaptureSession;
    private QuircHelper mQuircHelper;

    private ArrayList<QuircHelper.QrCode> mDetectedCodeList = new ArrayList<>();
    private Semaphore mCameraOpenCloseLock = new Semaphore(1);

    @Override
    protected void onCreate(Bundle savedInstanceState)
    {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_qr_detect);
        mTextureView = findViewById(R.id.preview);
        mTxtQrResult = findViewById(R.id.txtQrResult);

        mQuircHelper = new QuircHelper();
        mCamMgr = (android.hardware.camera2.CameraManager) getSystemService(Context.CAMERA_SERVICE);
        //mFgHandler = new Handler();
    }

    @Override
    protected void onResume()
    {
        super.onResume();

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M)
        {
            if (checkSelfPermission(
                    Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED)
            {
                requestPermissions(new String[]{Manifest.permission.CAMERA},
                        REQ_PERMISSION_CAMERA);
                return;
            }
        }

        mBgThread = new HandlerThread("BgHandler thread " + this);
        mBgThread.start();
        mBgHandler = new Handler(mBgThread.getLooper());
        mBgDcThread = new HandlerThread("BgDcHandler thread " + this);
        mBgDcThread.start();
        mBgDcHandler = new Handler(mBgDcThread.getLooper());

        if (mTextureView.isAvailable())
        {
            openCamera(mTextureView.getWidth(), mTextureView.getHeight());
        }
        else
        {
            mTextureView.setSurfaceTextureListener(mSurfaceTextureListener);
        }
    }

    @Override
    protected void onPause()
    {
        super.onPause();
        closeCamera();
        if (mBgThread != null)
        {
            mBgThread.quitSafely();
            try
            {
                mBgThread.join();
                mBgThread = null;
                mBgHandler = null;
            } catch (InterruptedException e)
            {
                e.printStackTrace();
            }
        }
        if (mBgDcThread != null)
        {
            mBgDcThread.quitSafely();
            try
            {
                mBgDcThread.join();
                mBgDcThread = null;
                mBgDcHandler = null;
            } catch (InterruptedException e)
            {
                e.printStackTrace();
            }
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions,
                                           @NonNull int[] grantResults)
    {
        switch (requestCode)
        {
            case REQ_PERMISSION_CAMERA:
            {
                if (grantResults.length <= 0
                        || grantResults[0] != PackageManager.PERMISSION_GRANTED)
                {
                    finish();
                }
            }
        }
    }

    @Override
    protected void onDestroy()
    {
        super.onDestroy();
    }

    private void openCamera(int width, int height)
    {
        setUpCameraOutputs(width, height);
        //configureTransform(width, height);
        try
        {
            if (!mCameraOpenCloseLock.tryAcquire(2500, TimeUnit.MILLISECONDS))
            {
                throw new RuntimeException("Time out waiting to lock camera opening.");
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M)
            {
                if (checkSelfPermission(
                        Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED)
                {
                    // TODO: Consider calling
                    //    Activity#requestPermissions
                    // here to request the missing permissions, and then overriding
                    //   public void onRequestPermissionsResult(int requestCode, String[] permissions,
                    //                                          int[] grantResults)
                    // to handle the case where the user grants the permission. See the documentation
                    // for Activity#requestPermissions for more details.
                    return;
                }
            }
            mCamMgr.openCamera(mCameraId, mStateCallback, mBgHandler);
        } catch (CameraAccessException e)
        {
            e.printStackTrace();
        } catch (InterruptedException e)
        {
            throw new RuntimeException("Interrupted while trying to lock camera opening.", e);
        }
    }

    private void closeCamera()
    {
        try
        {
            mCameraOpenCloseLock.acquire();
            if (mCaptureSession != null)
            {
                mCaptureSession.close();
                mCaptureSession = null;
            }
            if (mCamDev != null)
            {
                mCamDev.close();
                mCamDev = null;
            }
            if (mImageReader != null)
            {
                mImageReader.close();
                mImageReader = null;
            }
            mQuircHelper.release();
        } catch (InterruptedException e)
        {
            throw new RuntimeException("Interrupted while trying to lock camera closing.", e);
        } finally
        {
            mCameraOpenCloseLock.release();
        }
    }

    private void setUpCameraOutputs(int width, int height)
    {
        try
        {
            for (String camId : mCamMgr.getCameraIdList())
            {
                CameraCharacteristics characteristics
                        = mCamMgr.getCameraCharacteristics(camId);
                Integer facing = characteristics.get(CameraCharacteristics.LENS_FACING);
                if (facing == null || facing != CameraCharacteristics.LENS_FACING_BACK)
                {
                    continue;
                }
                mCameraId = camId;

                StreamConfigurationMap info = characteristics
                        .get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);
                // Choose the best preview size.
                final int suggestPreviewWidth = 800;
                final int suggestPreviewHeight = 600;
                // Only support portrait, so swap width and height.
                final int maxWidth = height;
                final int maxHeight = width;
                Size largest = Collections.max(
                        Arrays.asList(info.getOutputSizes(SurfaceTexture.class)),
                        new CompareSizesByArea());
                mPreviewSize = chooseOptimalSize(info.getOutputSizes(SurfaceTexture.class),
                        suggestPreviewWidth, suggestPreviewHeight,
                        maxWidth, maxHeight,
                        largest);
                mImageReader = ImageReader.newInstance(
                        mPreviewSize.getWidth(), mPreviewSize.getHeight(),
                        ImageFormat.YUV_420_888, 3);
                mImageReader.setOnImageAvailableListener(
                        mOnImageAvailableListener, mBgHandler);
                mQuircHelper.prepare(mPreviewSize.getWidth(), mPreviewSize.getHeight());
                break;
            }
        } catch (CameraAccessException e)
        {
            e.printStackTrace();
        }

    }

    private final ImageReader.OnImageAvailableListener mOnImageAvailableListener
            = new ImageReader.OnImageAvailableListener()
    {
        boolean mOnProcessing = false;
        @Override
        public void onImageAvailable(ImageReader reader)
        {
            if (mImageReader == null)
            {
                // ImageReader is already closed.
                return;
            }
            try (Image image = reader.acquireNextImage())
            {
                if (image == null)
                    return;
                final Image.Plane[] planes = image.getPlanes();
                if (mOnProcessing)
                    return;
                final ByteBuffer buffer = planes[0].getBuffer();
                final byte[] bytes = new byte[buffer.capacity()];
                buffer.get(bytes);
                mBgDcHandler.post(new Runnable()
                {
                    @Override
                    public void run()
                    {
                        mOnProcessing = true;
                        handleFrameBuffer(bytes);
                        mOnProcessing = false;
                    }
                });
            }
            catch (RuntimeException e)
            {
                e.printStackTrace();
            }
        }
    };

    private void handleFrameBuffer(final byte[] bytes)
    {
        mDetectedCodeList.clear();
        int gridsCount = mQuircHelper.detectGrids(bytes, false);
        if (gridsCount > 0)
            mQuircHelper.decode(mDetectedCodeList);

        if (mDetectedCodeList.isEmpty())
            return;
        final ArrayList<QuircHelper.QrCode> codeListCopy =
                (ArrayList<QuircHelper.QrCode>) mDetectedCodeList.clone();

        CamDetectActivity.this.runOnUiThread(new Runnable()
        {
            @Override
            public void run()
            {
                String display = "";
                Iterator<QuircHelper.QrCode> it = codeListCopy.iterator();
                do
                {
                    try
                    {
                        display += new String(it.next().mPayload, "UTF-8");
                    }
                    catch (UnsupportedEncodingException e)
                    {
                        e.printStackTrace();
                    }
                    if (it.hasNext())
                        display += "\n";
                }
                while (it.hasNext());
                mTxtQrResult.setText(display);
            }
        });
    }
    private final CameraDevice.StateCallback mStateCallback = new CameraDevice.StateCallback()
        {
            @Override
            public void onOpened(@NonNull CameraDevice cameraDevice)
            {
                // This method is called when the camera is opened.  We start camera preview here.
                mCameraOpenCloseLock.release();
                mCamDev = cameraDevice;
                createCameraPreviewSession();
            }

            @Override
            public void onDisconnected(@NonNull CameraDevice cameraDevice)
            {
                mCameraOpenCloseLock.release();
                cameraDevice.close();
                mCamDev = null;
            }

            @Override
            public void onError(@NonNull CameraDevice cameraDevice, int error)
            {
                mCameraOpenCloseLock.release();
                cameraDevice.close();
                mCamDev = null;
            }

        };

    private void createCameraPreviewSession()
    {
        try
        {
            SurfaceTexture texture = mTextureView.getSurfaceTexture();
            assert texture != null;

            // We configure the size of default buffer to be the size of camera preview we want.
            texture.setDefaultBufferSize(mPreviewSize.getWidth(), mPreviewSize.getHeight());
            // This is the output Surface we need to start preview.
            final Surface surface = new Surface(texture);

            List<Surface> outputs = Arrays.asList(
                    surface, mImageReader.getSurface());
            // Here, we create a CameraCaptureSession for camera preview.
            mCamDev.createCaptureSession(outputs,
                    new CameraCaptureSession.StateCallback()
                        {
                            @Override
                            public void onConfigured(@NonNull CameraCaptureSession cameraCaptureSession)
                            {
                                // The camera is already closed
                                if (null == mCamDev)
                                {
                                    return;
                                }

                                CaptureRequest.Builder previewRequestBuilder;
                                // We set up a CaptureRequest.Builder with the output Surface.
                                try
                                {
                                    previewRequestBuilder
                                            = mCamDev.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW);
                                    previewRequestBuilder.addTarget(surface);
                                    previewRequestBuilder.addTarget(mImageReader.getSurface());
                                    // When the session is ready, we start displaying the preview.
                                    mCaptureSession = cameraCaptureSession;
                                    // Auto focus should be continuous for camera preview.
                                    previewRequestBuilder.set(CaptureRequest.CONTROL_AF_MODE,
                                            CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_VIDEO);
                                    previewRequestBuilder.set(CaptureRequest.CONTROL_AE_MODE,
                                            CaptureRequest.CONTROL_AE_MODE_ON);
                                    // Flash is automatically enabled when necessary.
                                    //setAutoFlash(previewRequestBuilder);

                                    // Finally, we start displaying the camera preview.
                                    mCaptureSession.setRepeatingRequest(previewRequestBuilder.build(),
                                            null, mBgHandler);
                                }
                                catch (CameraAccessException e)
                                {
                                    e.printStackTrace();
                                }
                            }

                            @Override
                            public void onConfigureFailed(
                                    @NonNull CameraCaptureSession cameraCaptureSession)
                            {
                                showToast("Failed");
                            }
                        }, null
            );
        }
        catch (CameraAccessException e)
        {
            e.printStackTrace();
        }
    }

    private final TextureView.SurfaceTextureListener mSurfaceTextureListener
            = new TextureView.SurfaceTextureListener()
        {
            @Override
            public void onSurfaceTextureAvailable(SurfaceTexture texture, int width, int height)
            {
                openCamera(width, height);
            }

            @Override
            public void onSurfaceTextureSizeChanged(SurfaceTexture texture, int width, int height) {}

            @Override
            public boolean onSurfaceTextureDestroyed(SurfaceTexture texture) {
                return true;
            }

            @Override
            public void onSurfaceTextureUpdated(SurfaceTexture texture) {}

        };

    private static Size chooseOptimalSize(Size[] choices, int textureViewWidth,
                                          int textureViewHeight, int maxWidth,
                                          int maxHeight, Size aspectRatio)
    {
        // Collect the supported resolutions that are at least as big as the preview Surface
        List<Size> bigEnough = new ArrayList<>();
        // Collect the supported resolutions that are smaller than the preview Surface
        List<Size> notBigEnough = new ArrayList<>();
        int w = aspectRatio.getWidth();
        int h = aspectRatio.getHeight();
        for (Size option : choices)
        {
            if (option.getWidth() <= maxWidth && option.getHeight() <= maxHeight &&
                    option.getHeight() == option.getWidth() * h / w)
            {
                if (option.getWidth() >= textureViewWidth &&
                        option.getHeight() >= textureViewHeight)
                    bigEnough.add(option);
                else
                    notBigEnough.add(option);
            }
        }

        // Pick the smallest of those big enough. If there is no one big enough, pick the
        // largest of those not big enough.
        if (bigEnough.size() > 0) {
            return Collections.min(bigEnough, new CompareSizesByArea());
        } else if (notBigEnough.size() > 0) {
            return Collections.max(notBigEnough, new CompareSizesByArea());
        } else {
            Log.e(TAG, "Couldn't find any suitable preview size");
            for (Size option : choices)
            {
                if (option.getWidth() <= maxWidth)
                    return option;
            }
            return choices[0];
        }
    }

    private static class CompareSizesByArea implements Comparator<Size>
    {
        @Override
        public int compare(Size lhs, Size rhs)
        {
            // We cast here to ensure the multiplications won't overflow
            return Long.signum((long) lhs.getWidth() * lhs.getHeight()
                    - (long) rhs.getWidth() * rhs.getHeight());
        }
    }

    private void showToast(final String text)
    {
        this.runOnUiThread(new Runnable()
            {
                @Override
                public void run() {
                    Toast.makeText(CamDetectActivity.this, text, Toast.LENGTH_SHORT).show();
                }
            });
    }

}
