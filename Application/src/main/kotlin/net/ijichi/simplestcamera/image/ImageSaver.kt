package net.ijichi.simplestcamera.image

import android.media.Image
import java.io.File
import java.io.FileOutputStream
import java.io.IOException

/**
 * Created by ijichiyoshihito on 2017/02/10.
 */
/**
 * Saves a JPEG [Image] into the specified [File].
 */
class ImageSaver(
  /**
   * The JPEG image
   */
  private val mImage: Image,
  /**
   * The file we save the image into.
   */
  private val mFile: File) : Runnable {

  override fun run() {
    val buffer = mImage.planes[0].buffer
    val bytes = ByteArray(buffer.remaining())
    buffer.get(bytes)
    var output: FileOutputStream? = null
    try {
      output = FileOutputStream(mFile)
      output.write(bytes)
    } catch (e: IOException) {
      e.printStackTrace()
    } finally {
      mImage.close()
      if (null != output) {
        try {
          output.close()
        } catch (e: IOException) {
          e.printStackTrace()
        }

      }
    }
  }

}
