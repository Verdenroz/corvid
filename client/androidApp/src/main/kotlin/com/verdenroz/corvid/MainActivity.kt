package com.verdenroz.corvid

import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.verdenroz.corvid.app.CorvidApp
import org.koin.android.ext.android.inject
import org.publicvalue.multiplatform.oidc.appsupport.AndroidCodeAuthFlowFactory

class MainActivity : ComponentActivity() {
    private val authFlowFactory: AndroidCodeAuthFlowFactory by inject()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Nothing the app shows is meant to survive a screenshot or the
        // recents thumbnail (design.md, "Compromised devices").
        window.setFlags(WindowManager.LayoutParams.FLAG_SECURE, WindowManager.LayoutParams.FLAG_SECURE)
        authFlowFactory.registerActivity(this)
        enableEdgeToEdge()
        setContent { CorvidApp() }
    }
}
