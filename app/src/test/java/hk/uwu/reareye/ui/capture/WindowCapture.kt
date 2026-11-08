package hk.uwu.reareye.ui.capture

import android.app.Dialog
import android.graphics.Bitmap
import android.os.Handler
import android.os.Looper
import android.view.PixelCopy

/** Robolectric native-graphics capture of the actual Dialog window after Compose is idle. */
internal fun captureDialogWindowBitmap(dialog: Dialog): Bitmap {
    check(dialog.isShowing) { "Cannot capture a dismissed dialog" }
    val window = requireNotNull(dialog.window)
    val decor = window.decorView
    check(decor.width > 0 && decor.height > 0) { "Dialog has not been laid out" }
    val bitmap = Bitmap.createBitmap(decor.width, decor.height, Bitmap.Config.ARGB_8888)
    var result: Int? = null
    // Compose captureToImage resolves the Activity window for these Dialog semantics.
    // PixelCopy receives the actual window and uses Robolectric's hardware/native renderer,
    // unlike decor.draw(Canvas(bitmap)), which cannot draw RuntimeShader content.
    PixelCopy.request(window, bitmap, { result = it }, Handler(Looper.getMainLooper()))
    check(result == PixelCopy.SUCCESS) { "Native dialog PixelCopy failed or did not complete: $result" }
    return bitmap
}
