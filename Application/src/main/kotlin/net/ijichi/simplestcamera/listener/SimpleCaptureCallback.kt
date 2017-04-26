package net.ijichi.simplestcamera.listener

import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCaptureSession.CaptureCallback
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.CaptureResult
import android.hardware.camera2.TotalCaptureResult

/**
 * Created by ijichiyoshihito on 2017/02/10.
 */
abstract class SimpleCaptureCallback: CaptureCallback(){

  abstract fun process(result: CaptureResult)

  override fun onCaptureProgressed(session: CameraCaptureSession, request: CaptureRequest, partialResult: CaptureResult) {
    process(partialResult)
  }
  override fun onCaptureCompleted(session: CameraCaptureSession, request: CaptureRequest, result: TotalCaptureResult) {
    process(result)
  }

}