package com.verdenroz.corvid

import android.Manifest
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import com.verdenroz.corvid.app.BackgroundDropController
import com.verdenroz.corvid.app.CorvidApp
import org.koin.android.ext.android.inject
import org.publicvalue.multiplatform.oidc.appsupport.AndroidCodeAuthFlowFactory

class MainActivity : ComponentActivity() {
    private val authFlowFactory: AndroidCodeAuthFlowFactory by inject()
    private val backgroundDropController: BackgroundDropController by inject()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Nothing the app shows is meant to survive a screenshot or the recents thumbnail.
        window.setFlags(WindowManager.LayoutParams.FLAG_SECURE, WindowManager.LayoutParams.FLAG_SECURE)
        authFlowFactory.registerActivity(this)
        enableEdgeToEdge()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerForActivityResult(ActivityResultContracts.RequestPermission()) {}.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
        setContent { CorvidApp() }
    }

    // Single-activity app, so this activity's own stop/start is the app's
    // background/foreground signal.
    override fun onStop() {
        super.onStop()
        backgroundDropController.enteredBackground()
    }

    override fun onStart() {
        super.onStart()
        backgroundDropController.enteredForeground()
    }
}
