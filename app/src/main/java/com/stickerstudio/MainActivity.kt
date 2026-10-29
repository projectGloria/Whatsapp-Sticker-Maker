package com.stickerstudio

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.*
import androidx.core.view.WindowCompat

class MainActivity : ComponentActivity() {
    private val model by viewModels<StudioModel>()
    private val enablePack =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            if (result.resultCode == RESULT_CANCELED) {
                model.message =
                    result.data?.getStringExtra("validation_error")
                        ?: "WhatsApp did not confirm adding the pack. You can try again."
            } else model.message = "WhatsApp finished the add-pack request."
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            val dark = isSystemInDarkTheme()
            SideEffect {
                WindowCompat.getInsetsController(window, window.decorView).apply {
                    isAppearanceLightStatusBars = !dark
                    isAppearanceLightNavigationBars = !dark
                }
            }
            StudioApp(model) { pack, target ->
                try {
                    enablePack.launch(
                        Intent("com.whatsapp.intent.action.ENABLE_STICKER_PACK")
                            .setPackage(target)
                            .putExtra("sticker_pack_id", pack.id)
                            .putExtra("sticker_pack_authority", "$packageName.stickers")
                            .putExtra("sticker_pack_name", pack.name)
                    )
                } catch (e: Exception) {
                    model.message = "Could not open WhatsApp: ${e.message}"
                }
            }
        }
        if (savedInstanceState == null) receive(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        // Keep the launch identity stable; the model consumes each new share directly.
        receive(intent)
    }

    @Suppress("DEPRECATION")
    private fun receive(intent: Intent?) {
        if (intent?.action != Intent.ACTION_SEND && intent?.action != Intent.ACTION_SEND_MULTIPLE)
            return
        val uris =
            buildList {
                    if (intent.action == Intent.ACTION_SEND_MULTIPLE)
                        addAll(
                            intent.getParcelableArrayListExtra<Uri>(Intent.EXTRA_STREAM).orEmpty()
                        )
                    else intent.getParcelableExtra<Uri>(Intent.EXTRA_STREAM)?.let { add(it) }
                    intent.clipData?.let { clip ->
                        for (i in 0 until clip.itemCount) clip.getItemAt(i).uri?.let { add(it) }
                    }
                }
                .distinct()
                .filter { it.scheme == "content" || it.scheme == "file" }
        if (uris.isEmpty())
            model.message =
                "No readable media was shared. Share actual image or MP4 files; pack links and codes are not supported."
        else model.receive(uris)
        intent.action = null
    }
}
