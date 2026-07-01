package com.example.ai_assist

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.view.WindowManager
import com.example.ai_assist.databinding.ActivityQrScanBinding
import com.ffalcon.mercury.android.sdk.touch.TempleAction
import com.ffalcon.mercury.android.sdk.ui.activity.BaseMirrorActivity
import com.google.zxing.BarcodeFormat
import com.google.zxing.ResultPoint
import com.journeyapps.barcodescanner.BarcodeCallback
import com.journeyapps.barcodescanner.BarcodeResult
import com.journeyapps.barcodescanner.DefaultDecoderFactory
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch

class QrScanActivity : BaseMirrorActivity<ActivityQrScanBinding>() {

    private var hasReturnedResult = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        mBindingPair.updateView {
            barcodeScanner.barcodeView.decoderFactory =
                DefaultDecoderFactory(listOf(BarcodeFormat.QR_CODE))
            barcodeScanner.initializeFromIntent(intent)
            barcodeScanner.setStatusText("")
            barcodeScanner.decodeContinuous(scanCallback)
        }

        lifecycleScope.launch {
            templeActionViewModel.state.collect { action ->
                when (action) {
                    is TempleAction.DoubleClick -> finish()
                    else -> Unit
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        mBindingPair.updateView {
            barcodeScanner.resume()
        }
    }

    override fun onPause() {
        mBindingPair.updateView {
            barcodeScanner.pause()
        }
        super.onPause()
    }

    private val scanCallback = object : BarcodeCallback {
        override fun barcodeResult(result: BarcodeResult?) {
            if (hasReturnedResult) return
            val text = result?.text ?: return
            hasReturnedResult = true
            val data = Intent().putExtra(EXTRA_QR_CONTENT, text)
            setResult(Activity.RESULT_OK, data)
            finish()
        }

        override fun possibleResultPoints(resultPoints: MutableList<ResultPoint>?) = Unit
    }

    companion object {
        const val EXTRA_QR_CONTENT = "qr_content"
    }
}
