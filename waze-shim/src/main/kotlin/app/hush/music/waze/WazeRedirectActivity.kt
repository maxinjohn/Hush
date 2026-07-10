package app.hush.music.waze

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.widget.Toast

class WazeRedirectActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        try {
            val hushIntent = packageManager.getLaunchIntentForPackage("app.hush.music")
            if (hushIntent == null) {
                Toast.makeText(this, "Hush Music is not installed", Toast.LENGTH_SHORT).show()
            } else {
                hushIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                startActivity(hushIntent)
            }
        } catch (_: Exception) {
            Toast.makeText(this, "Unable to open Hush Music", Toast.LENGTH_SHORT).show()
        }

        finish()
    }
}
