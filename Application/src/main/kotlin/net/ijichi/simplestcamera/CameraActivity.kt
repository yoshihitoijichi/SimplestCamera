package net.ijichi.simplestcamera

import android.app.Activity
import android.content.Context
import android.media.AudioManager
import android.os.Bundle


class CameraActivity : Activity() {

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    setContentView(R.layout.activity_camera)

    setupSystemVolume()
    if (null == savedInstanceState)setupCamera2Fragment()
  }

  private fun setupSystemVolume(){
    volumeControlStream = AudioManager.STREAM_SYSTEM  // デフォルトをシステム音にフォーカス
    val am = getSystemService(Context.AUDIO_SERVICE) as AudioManager
    am.setStreamVolume(AudioManager.STREAM_SYSTEM, 0, 0)
  }

  private fun setupCamera2Fragment(){
    fragmentManager.beginTransaction()
      .replace(R.id.container, Camera2BasicFragment())
      .commit()
  }

}
