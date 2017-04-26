package net.ijichi.simplestcamera.dialog

import android.Manifest
import android.app.AlertDialog
import android.app.Dialog
import android.app.DialogFragment
import android.os.Bundle
import android.support.v13.app.FragmentCompat
import net.ijichi.simplestcamera.Camera2BasicFragment
import net.ijichi.simplestcamera.R

/**
 * Created by ijichiyoshihito on 2017/02/10.
 */
/**
 * Shows OK/Cancel confirmation dialog about camera permission.
 */
class ConfirmationDialog : DialogFragment() {

  override fun onCreateDialog(savedInstanceState: Bundle): Dialog {
    val parent = parentFragment
    return AlertDialog.Builder(activity)
      .setMessage(R.string.request_permission)
      .setPositiveButton(android.R.string.ok) { dialog, which ->
        FragmentCompat.requestPermissions(parent,
          arrayOf(Manifest.permission.CAMERA),
          Camera2BasicFragment.REQUEST_CAMERA_PERMISSION)
      }
      .setNegativeButton(android.R.string.cancel) { dialog, which ->
        val activity = parent.activity
        activity?.finish()
      }.create()
  }
}
