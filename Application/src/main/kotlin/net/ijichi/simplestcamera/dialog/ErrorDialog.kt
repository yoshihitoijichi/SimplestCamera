package net.ijichi.simplestcamera.dialog

import android.app.AlertDialog
import android.app.Dialog
import android.app.DialogFragment
import android.os.Bundle

/**
 * Created by ijichiyoshihito on 2017/02/10.
 */
/**
 * Shows an error message dialog.
 */
class ErrorDialog : DialogFragment() {

  override fun onCreateDialog(savedInstanceState: Bundle): Dialog {
    val activity = activity
    return AlertDialog.Builder(activity)
      .setMessage(arguments.getString(ARG_MESSAGE))
      .setPositiveButton(android.R.string.ok) { dialogInterface, i -> activity.finish() }
      .create()
  }

  companion object {

    private val ARG_MESSAGE = "message"

    fun newInstance(message: String): ErrorDialog {
      val dialog = ErrorDialog()
      val args = Bundle()
      args.putString(ARG_MESSAGE, message)
      dialog.arguments = args
      return dialog
    }
  }

}