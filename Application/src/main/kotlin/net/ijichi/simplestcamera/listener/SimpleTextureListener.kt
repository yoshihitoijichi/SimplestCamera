package net.ijichi.simplestcamera.listener

import android.graphics.SurfaceTexture
import android.view.TextureView

/**
 * Created by ijichiyoshihito on 2017/02/10.
 */
interface SimpleTextureListener: TextureView.SurfaceTextureListener{

  override fun onSurfaceTextureAvailable(texture: SurfaceTexture, width: Int, height: Int) {
  }
  override fun onSurfaceTextureSizeChanged(texture: SurfaceTexture, width: Int, height: Int) {
  }
  override fun onSurfaceTextureDestroyed(texture: SurfaceTexture): Boolean {
    return true
  }
  override fun onSurfaceTextureUpdated(texture: SurfaceTexture) {}
}