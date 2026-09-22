package app.systemresponse

import android.app.Activity
import android.graphics.Color
import android.view.Gravity
import android.view.WindowInsets
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import com.journeyapps.barcodescanner.CaptureActivity
import com.journeyapps.barcodescanner.DecoratedBarcodeView

class PairingScannerActivity : CaptureActivity() {
    override fun initializeContent(): DecoratedBarcodeView {
        fun dp(value: Int) = (resources.displayMetrics.density * value).toInt()
        val root = FrameLayout(this)
        val scanner = DecoratedBarcodeView(this)
        root.addView(scanner, FrameLayout.LayoutParams(-1, -1))
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(16), dp(20), dp(16)); setBackgroundColor(0xDD101517.toInt())
            addView(TextView(this@PairingScannerActivity).apply {
                text = "Наведи камеру на QR-код в Seamless на Mac"; textSize = 18f; setTextColor(Color.WHITE)
            })
            addView(Button(this@PairingScannerActivity).apply {
                text = "Закрыть сканер"; isAllCaps = false
                setOnClickListener { setResult(Activity.RESULT_CANCELED); finish() }
            })
        }
        root.addView(header, FrameLayout.LayoutParams(-1, -2, Gravity.TOP))
        header.setOnApplyWindowInsetsListener { view, insets ->
            val bars = insets.getInsets(WindowInsets.Type.systemBars())
            view.setPadding(bars.left + dp(20), bars.top + dp(16), bars.right + dp(20), dp(16)); insets
        }
        setContentView(root)
        return scanner
    }
}
