package net.ijichi.simplestcamera

import android.Manifest
import android.app.Fragment
import android.content.Context
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.graphics.*
import android.hardware.camera2.*
import android.media.ImageReader
import android.media.MediaScannerConnection
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.HandlerThread
import android.support.v13.app.FragmentCompat
import android.support.v4.content.ContextCompat
import android.util.Log
import android.util.Size
import android.util.SparseIntArray
import android.view.*
import android.widget.Toast
import net.ijichi.simplestcamera.dialog.ConfirmationDialog
import net.ijichi.simplestcamera.dialog.ErrorDialog
import net.ijichi.simplestcamera.image.ImageSaver
import net.ijichi.simplestcamera.listener.SimpleCaptureCallback
import net.ijichi.simplestcamera.listener.SimpleTextureListener
import net.ijichi.simplestcamera.view.AutoFitTextureView
import java.io.File
import java.util.*
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit

class Camera2BasicFragment: Fragment(), FragmentCompat.OnRequestPermissionsResultCallback {

  companion object {
    private val ORIENTATIONS = SparseIntArray()
    val REQUEST_CAMERA_PERMISSION = 1
    private val FRAGMENT_DIALOG = "dialog"

    init {
      ORIENTATIONS.append(Surface.ROTATION_0, 90)
      ORIENTATIONS.append(Surface.ROTATION_90, 0)
      ORIENTATIONS.append(Surface.ROTATION_180, 270)
      ORIENTATIONS.append(Surface.ROTATION_270, 180)
    }

    private val TAG = "Camera2BasicFragment"
    private val STATE_PREVIEW = 0
    private val STATE_WAITING_LOCK = 1
    private val STATE_WAITING_PRECAPTURE = 2
    private val STATE_WAITING_NON_PRECAPTURE = 3
    private val STATE_PICTURE_TAKEN = 4
    private val MAX_PREVIEW_WIDTH = 1920
    private val MAX_PREVIEW_HEIGHT = 1080

    private fun chooseOptimalSize(choices: Array<Size>, textureViewWidth: Int, textureViewHeight: Int, maxWidth: Int, maxHeight: Int, aspectRatio: Size): Size {
      println("chooseOptimalSize")
      println("textureViewWidth = $textureViewWidth")
      println("textureViewHeight = $textureViewHeight")
      println("maxWidth = $maxWidth")
      println("maxHeight = $maxHeight")
      println("aspectRatio.width = ${aspectRatio.width}")
      println("aspectRatio.height = ${aspectRatio.height}")

      val bigEnough = ArrayList<Size>()
      val notBigEnough = ArrayList<Size>()
      val w = aspectRatio.width
      val h = aspectRatio.height
      for (option in choices) {
        if (option.width <= maxWidth && option.height <= maxHeight && option.height == option.width * h / w) {
          if (option.width >= textureViewWidth && option.height >= textureViewHeight) {
            bigEnough.add(option)
          } else {
            notBigEnough.add(option)
          }
        }
      }

      if (bigEnough.size > 0) {
        return Collections.min(bigEnough, CompareSizesByArea())
      } else if (notBigEnough.size > 0) {
        return Collections.max(notBigEnough, CompareSizesByArea())
      } else {
        Log.e(TAG, "Couldn't find any suitable preview size")
        return choices[0]
      }
    }
  }

  /** [TextureView.SurfaceTextureListener] handles several lifecycle events on a [TextureView]. */
  private val mSurfaceTextureListener = object: SimpleTextureListener {
    override fun onSurfaceTextureAvailable(texture: SurfaceTexture, width: Int, height: Int) {
      openCamera(width, height)
    }
    override fun onSurfaceTextureSizeChanged(texture: SurfaceTexture, width: Int, height: Int) {
      configureTransform(width, height)
    }
  }

  private var mCameraId: String? = null
  private var mTextureView: AutoFitTextureView? = null
  private var mCaptureSession: CameraCaptureSession? = null
  private var mCameraDevice: CameraDevice? = null
  private var mPreviewSize: Size? = null

  private var mBackgroundThread: HandlerThread? = null
  private var mBackgroundHandler: Handler? = null
  private var mImageReader: ImageReader? = null
  private var mFile: File? = null
  private val mOnImageAvailableListener = ImageReader.OnImageAvailableListener { reader -> mBackgroundHandler!!.post(ImageSaver(reader.acquireNextImage(), mFile ?: File(""))) }
  private var mPreviewRequestBuilder: CaptureRequest.Builder? = null
  private var mPreviewRequest: CaptureRequest? = null
  private var mState = STATE_PREVIEW
  private val mCameraOpenCloseLock = Semaphore(1)
  private var mFlashSupported: Boolean = false
  private var mSensorOrientation: Int = 0

  /** A [CameraCaptureSession.CaptureCallback] that handles events related to JPEG capture. */
  private val mCaptureCallback = object: SimpleCaptureCallback() {
    override fun process(result: CaptureResult) {
      when (mState) {
        STATE_PREVIEW -> {
        }// We have nothing to do when the camera preview is working normally.
        STATE_WAITING_LOCK -> {
          val afState = result.get(CaptureResult.CONTROL_AF_STATE)
          if (afState == null) {
            captureStillPicture()
          } else if (CaptureResult.CONTROL_AF_STATE_FOCUSED_LOCKED == afState || CaptureResult.CONTROL_AF_STATE_NOT_FOCUSED_LOCKED == afState) {
            // CONTROL_AE_STATE can be null on some devices
            val aeState = result.get(CaptureResult.CONTROL_AE_STATE)
            if (aeState == null || aeState === CaptureResult.CONTROL_AE_STATE_CONVERGED) {
              mState = STATE_PICTURE_TAKEN
              captureStillPicture()
            } else {
              runPrecaptureSequence()
            }
          }
        }
        STATE_WAITING_PRECAPTURE -> {
          // CONTROL_AE_STATE can be null on some devices
          val aeState = result.get(CaptureResult.CONTROL_AE_STATE)
          if (aeState == null ||
            aeState === CaptureResult.CONTROL_AE_STATE_PRECAPTURE ||
            aeState === CaptureRequest.CONTROL_AE_STATE_FLASH_REQUIRED) {
            mState = STATE_WAITING_NON_PRECAPTURE
          }
        }
        STATE_WAITING_NON_PRECAPTURE -> {
          // CONTROL_AE_STATE can be null on some devices
          val aeState = result.get(CaptureResult.CONTROL_AE_STATE)
          if (aeState == null || aeState !== CaptureResult.CONTROL_AE_STATE_PRECAPTURE) {
            mState = STATE_PICTURE_TAKEN
            captureStillPicture()
          }
        }
      }
    }
  }

  /**
   * Shows a [Toast] on the UI thread.
   * @param text The message to show
   */
  private fun showToast(text: String) {
    activity?.runOnUiThread { Toast.makeText(activity, text, Toast.LENGTH_SHORT).show() }
  }

  override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View? {
    return inflater.inflate(R.layout.fragment_camera2_basic, container, false)
  }

  override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
    view.findViewById(R.id.picture).setOnClickListener {
      lockFocus()
    }
    mTextureView = view.findViewById(R.id.texture) as AutoFitTextureView
  }

  override fun onActivityCreated(savedInstanceState: Bundle?) {
    super.onActivityCreated(savedInstanceState)
//    mFile = File(activity.getExternalFilesDir(null), Date().time.toString() + ".jpg")
  }

  override fun onResume() {
    super.onResume()
    startBackgroundThread()
    if (mTextureView!!.isAvailable) {
      openCamera(mTextureView!!.width, mTextureView!!.height)
    } else {
      mTextureView!!.surfaceTextureListener = mSurfaceTextureListener
    }
  }

  override fun onPause() {
    closeCamera()
    stopBackgroundThread()
    super.onPause()
  }

  private fun requestCameraPermission() {
    if (FragmentCompat.shouldShowRequestPermissionRationale(this, Manifest.permission.CAMERA)) {
      ConfirmationDialog().show(childFragmentManager, FRAGMENT_DIALOG)
    } else {
      FragmentCompat.requestPermissions(this, arrayOf(Manifest.permission.CAMERA), REQUEST_CAMERA_PERMISSION)
    }
  }

  override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<String>, grantResults: IntArray) {
    if (requestCode == REQUEST_CAMERA_PERMISSION) {
      if (grantResults.size != 1 || grantResults[0] != PackageManager.PERMISSION_GRANTED) {
        ErrorDialog.newInstance(getString(R.string.request_permission)).show(childFragmentManager, FRAGMENT_DIALOG)
      }
    } else {
      super.onRequestPermissionsResult(requestCode, permissions, grantResults)
    }
  }

  private fun setUpCameraOutputs(width: Int, height: Int) {
    val activity = activity
    val manager = activity.getSystemService(Context.CAMERA_SERVICE) as CameraManager
    try {
      for (cameraId in manager.cameraIdList) {
        val characteristics = manager.getCameraCharacteristics(cameraId)

        // We don't use a front facing camera in this sample.
        val facing = characteristics.get(CameraCharacteristics.LENS_FACING)
        if (facing != null && facing === CameraCharacteristics.LENS_FACING_FRONT) {
          continue
        }

        val map = characteristics.get(
          CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP) ?: continue

        // For still image captures, we use the largest available size.
        val largest = Collections.max(
          Arrays.asList(*map.getOutputSizes(ImageFormat.JPEG)), CompareSizesByArea())
        mImageReader = ImageReader.newInstance(largest.width, largest.height, ImageFormat.JPEG, /*maxImages*/2)
        mImageReader!!.setOnImageAvailableListener(mOnImageAvailableListener, mBackgroundHandler)

        // Find out if we need to swap dimension to get the preview size relative to sensor coordinate.
        val displayRotation = activity.windowManager.defaultDisplay.rotation

        mSensorOrientation = characteristics.get(CameraCharacteristics.SENSOR_ORIENTATION)!!
        var swappedDimensions = false
        when (displayRotation) {
          Surface.ROTATION_0, Surface.ROTATION_180 -> if (mSensorOrientation == 90 || mSensorOrientation == 270) {
            swappedDimensions = true
          }
          Surface.ROTATION_90, Surface.ROTATION_270 -> if (mSensorOrientation == 0 || mSensorOrientation == 180) {
            swappedDimensions = true
          }
          else -> Log.e(TAG, "Display rotation is invalid: " + displayRotation)
        }

        val displaySize = Point()
        activity.windowManager.defaultDisplay.getSize(displaySize)
        var rotatedPreviewWidth = width
        var rotatedPreviewHeight = height
        var maxPreviewWidth = displaySize.x
        var maxPreviewHeight = displaySize.y

        if (swappedDimensions) {
          rotatedPreviewWidth = height
          rotatedPreviewHeight = width
          maxPreviewWidth = displaySize.y
          maxPreviewHeight = displaySize.x
        }

        if (maxPreviewWidth > MAX_PREVIEW_WIDTH) {
          maxPreviewWidth = MAX_PREVIEW_WIDTH
        }

        if (maxPreviewHeight > MAX_PREVIEW_HEIGHT) {
          maxPreviewHeight = MAX_PREVIEW_HEIGHT
        }

        println()
        mPreviewSize = chooseOptimalSize(map.getOutputSizes<SurfaceTexture>(SurfaceTexture::class.java),
          rotatedPreviewWidth, rotatedPreviewHeight, maxPreviewWidth,
          maxPreviewHeight, largest)

        // We fit the aspect ratio of TextureView to the size of preview we picked.
        val orientation = resources.configuration.orientation
        if (orientation == Configuration.ORIENTATION_LANDSCAPE) {
          mTextureView!!.setAspectRatio(
            mPreviewSize!!.width, mPreviewSize!!.height)
        } else {
          mTextureView!!.setAspectRatio(
            mPreviewSize!!.height, mPreviewSize!!.width)
        }

        // Check if the flash is supported.
//        val available = characteristics.get(CameraCharacteristics.FLASH_INFO_AVAILABLE)
//        mFlashSupported = available ?: false
        mFlashSupported = false // flash 機能不要

        mCameraId = cameraId
        return
      }
    } catch (e: CameraAccessException) {
      e.printStackTrace()
    } catch (e: NullPointerException) {
      // Currently an NPE is thrown when the Camera2API is used but not supported on the device this code runs.
      ErrorDialog.newInstance(getString(R.string.camera_error)).show(childFragmentManager, FRAGMENT_DIALOG)
    }

  }

  /**
   * Opens the camera specified by [Camera2BasicFragment.mCameraId].
   */
  private fun openCamera(width: Int, height: Int) {
    if (ContextCompat.checkSelfPermission(activity, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
      requestCameraPermission()
      return
    }
    setUpCameraOutputs(width, height)
    configureTransform(width, height)
    val activity = activity
    val manager = activity.getSystemService(Context.CAMERA_SERVICE) as CameraManager
    try {
      if (!mCameraOpenCloseLock.tryAcquire(2500, TimeUnit.MILLISECONDS)) {
        throw RuntimeException("Time out waiting to lock camera opening.")
      }
      manager.openCamera(mCameraId!!, object : CameraDevice.StateCallback() {
        override fun onOpened(cameraDevice: CameraDevice) {
          // This method is called when the camera is opened.  We start camera preview here.
          mCameraOpenCloseLock.release()
          mCameraDevice = cameraDevice
          createCameraPreviewSession()
        }
        override fun onDisconnected(cameraDevice: CameraDevice) {
          mCameraOpenCloseLock.release()
          cameraDevice.close()
          mCameraDevice = null
        }
        override fun onError(cameraDevice: CameraDevice, error: Int) {
          mCameraOpenCloseLock.release()
          cameraDevice.close()
          mCameraDevice = null
          val activity = activity
          activity?.finish()
        }
      }, mBackgroundHandler)
    } catch (e: CameraAccessException) {
      e.printStackTrace()
    } catch (e: InterruptedException) {
      throw RuntimeException("Interrupted while trying to lock camera opening.", e)
    }

  }

  private fun closeCamera() {
    try {
      mCameraOpenCloseLock.acquire()
      if (null != mCaptureSession) {
        mCaptureSession!!.close()
        mCaptureSession = null
      }
      if (null != mCameraDevice) {
        mCameraDevice!!.close()
        mCameraDevice = null
      }
      if (null != mImageReader) {
        mImageReader!!.close()
        mImageReader = null
      }
    } catch (e: InterruptedException) {
      throw RuntimeException("Interrupted while trying to lock camera closing.", e)
    } finally {
      mCameraOpenCloseLock.release()
    }
  }

  private fun startBackgroundThread() {
    mBackgroundThread = HandlerThread("CameraBackground")
    mBackgroundThread!!.start()
    mBackgroundHandler = Handler(mBackgroundThread!!.looper)
  }

  private fun stopBackgroundThread() {
    mBackgroundThread!!.quitSafely()
    try {
      mBackgroundThread!!.join()
      mBackgroundThread = null
      mBackgroundHandler = null
    } catch (e: InterruptedException) {
      e.printStackTrace()
    }
  }

  private fun createCameraPreviewSession() {
    try {
      val texture = mTextureView!!.surfaceTexture!!

      // We configure the size of default buffer to be the size of camera preview we want.
      texture.setDefaultBufferSize(mPreviewSize!!.width, mPreviewSize!!.height)

      // This is the output Surface we need to start preview.
      val surface = Surface(texture)

      // We set up a CaptureRequest.Builder with the output Surface.
      mPreviewRequestBuilder = mCameraDevice!!.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW)
      mPreviewRequestBuilder!!.addTarget(surface)

      // Here, we create a CameraCaptureSession for camera preview.
      mCameraDevice!!.createCaptureSession(Arrays.asList(surface, mImageReader!!.surface),
        object : CameraCaptureSession.StateCallback() {

          override fun onConfigured(cameraCaptureSession: CameraCaptureSession) {
            // The camera is already closed
            if (null == mCameraDevice) return

            // When the session is ready, we start displaying the preview.
            mCaptureSession = cameraCaptureSession
            try {
              // Auto focus should be continuous for camera preview.
              mPreviewRequestBuilder!!.set(CaptureRequest.CONTROL_AF_MODE,
                CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE)
              // Flash is automatically enabled when necessary.
              setAutoFlash(mPreviewRequestBuilder!!)

              // Finally, we start displaying the camera preview.
              mPreviewRequest = mPreviewRequestBuilder!!.build()
              mCaptureSession!!.setRepeatingRequest(mPreviewRequest!!, mCaptureCallback, mBackgroundHandler)
            } catch (e: CameraAccessException) {
              e.printStackTrace()
            }

          }

          override fun onConfigureFailed(cameraCaptureSession: CameraCaptureSession) {
            showToast("Failed")
          }
        }, null
      )
    } catch (e: CameraAccessException) {
      e.printStackTrace()
    }
  }

  private fun configureTransform(viewWidth: Int, viewHeight: Int) {
    if (null == mTextureView || null == mPreviewSize || null == activity) return

    val rotation = activity.windowManager.defaultDisplay.rotation
    val matrix = Matrix()
    val viewRect = RectF(0f, 0f, viewWidth.toFloat(), viewHeight.toFloat())
    val bufferRect = RectF(0f, 0f, mPreviewSize!!.height.toFloat(), mPreviewSize!!.width.toFloat())
    val centerX = viewRect.centerX()
    val centerY = viewRect.centerY()
    if (Surface.ROTATION_90 == rotation || Surface.ROTATION_270 == rotation) {
      bufferRect.offset(centerX - bufferRect.centerX(), centerY - bufferRect.centerY())
      matrix.setRectToRect(viewRect, bufferRect, Matrix.ScaleToFit.FILL)
      val scale = Math.max(
        viewHeight.toFloat() / mPreviewSize!!.height,
        viewWidth.toFloat() / mPreviewSize!!.width)
      matrix.postScale(scale, scale, centerX, centerY)
      matrix.postRotate((90 * (rotation - 2)).toFloat(), centerX, centerY)
    } else if (Surface.ROTATION_180 == rotation) {
      matrix.postRotate(180f, centerX, centerY)
    }
    mTextureView!!.setTransform(matrix)
  }

  private fun lockFocus() {
//    val dir = Environment.getExternalStorageDirectory().path
    val dir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES)
    println("dir = $dir")
    mFile = File(dir, Date().time.toString() + ".jpg")
//    mFile = File(activity.getExternalFilesDir(null), Date().time.toString() + ".jpg")

    // This is how to tell the camera to lock focus.
    mPreviewRequestBuilder!!.set(CaptureRequest.CONTROL_AF_TRIGGER, CameraMetadata.CONTROL_AF_TRIGGER_START)
    // Tell #mCaptureCallback to wait for the lock.
    mState = STATE_WAITING_LOCK
    mCaptureSession!!.capture(mPreviewRequestBuilder!!.build(), mCaptureCallback, mBackgroundHandler)
  }

  private fun runPrecaptureSequence() {
    // This is how to tell the camera to trigger.
    mPreviewRequestBuilder!!.set(CaptureRequest.CONTROL_AE_PRECAPTURE_TRIGGER,
      CaptureRequest.CONTROL_AE_PRECAPTURE_TRIGGER_START)
    // Tell #mCaptureCallback to wait for the precapture sequence to be set.
    mState = STATE_WAITING_PRECAPTURE
    mCaptureSession!!.capture(mPreviewRequestBuilder!!.build(), mCaptureCallback, mBackgroundHandler)
  }

  private fun captureStillPicture() {
    if (null == activity || null == mCameraDevice) return

    // This is the CaptureRequest.Builder that we use to take a picture.
    val captureBuilder = mCameraDevice!!.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE)
    captureBuilder.addTarget(mImageReader!!.surface)

    // Use the same AE and AF modes as the preview.
    captureBuilder.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE)
    setAutoFlash(captureBuilder)

    // Orientation
    val rotation = activity.windowManager.defaultDisplay.rotation
    captureBuilder.set(CaptureRequest.JPEG_ORIENTATION, getOrientation(rotation))

    val CaptureCallback = object : CameraCaptureSession.CaptureCallback() {
      override fun onCaptureCompleted(session: CameraCaptureSession, request: CaptureRequest, result: TotalCaptureResult) {
        showToast("Saved: " + mFile!!)
        Log.d(TAG, mFile!!.toString())
        showToast("success")

        // アンドロイドのデータベースへ登録
        registAndroidDB(mFile)
        unlockFocus()
      }
    }

    mCaptureSession!!.stopRepeating()
    mCaptureSession!!.capture(captureBuilder.build(), CaptureCallback, null)
  }

    private fun registAndroidDB(file: File?) {
//      val mediaScanIntent = Intent(Intent.ACTION_MEDIA_SCANNER_SCAN_FILE)
//      val contentUri = Uri.fromFile(file)
//      mediaScanIntent.data = contentUri
//      activity.sendBroadcast(mediaScanIntent)

//      val values = ContentValues()
//      values.put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
//      values.put(MediaStore.Images.Media.TITLE, file?.name)
//      values.put("_data", file?.canonicalPath)
//      activity.contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)

      MediaScannerConnection.scanFile(activity.applicationContext, arrayOf(file?.canonicalPath ?: "") , arrayOf("image/jpeg"), null)
    }

  private fun getOrientation(rotation: Int): Int {
    return (ORIENTATIONS.get(rotation) + mSensorOrientation + 270) % 360
  }

  private fun unlockFocus() {
    // Reset the auto-focus trigger
    mPreviewRequestBuilder!!.set(CaptureRequest.CONTROL_AF_TRIGGER, CameraMetadata.CONTROL_AF_TRIGGER_CANCEL)
    setAutoFlash(mPreviewRequestBuilder!!)
    mCaptureSession!!.capture(mPreviewRequestBuilder!!.build(), mCaptureCallback, mBackgroundHandler)
    // After this, the camera will go back to the normal state of preview.
    mState = STATE_PREVIEW
    mCaptureSession!!.setRepeatingRequest(mPreviewRequest!!, mCaptureCallback, mBackgroundHandler)
  }

  private fun setAutoFlash(requestBuilder: CaptureRequest.Builder) {
    if (mFlashSupported) requestBuilder.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON_AUTO_FLASH)
  }

  internal class CompareSizesByArea : Comparator<Size> {
    override fun compare(lhs: Size, rhs: Size): Int {
      return java.lang.Long.signum(lhs.width.toLong() * lhs.height - rhs.width.toLong() * rhs.height)
    }
  }

}
