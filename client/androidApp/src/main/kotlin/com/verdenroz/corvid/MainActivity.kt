package com.verdenroz.corvid

import android.content.Intent
import android.content.pm.ApplicationInfo
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import com.verdenroz.corvid.app.AppPresence
import com.verdenroz.corvid.app.BackgroundDropController
import com.verdenroz.corvid.app.CorvidApp
import com.verdenroz.corvid.core.auth.privateAndroidFlowQualifier
import org.koin.android.ext.android.inject
import org.publicvalue.multiplatform.oidc.appsupport.AndroidCodeAuthFlowFactory

class MainActivity : ComponentActivity() {
    private val authFlowFactory: AndroidCodeAuthFlowFactory by inject()
    private val privateAuthFlowFactory: AndroidCodeAuthFlowFactory by inject(privateAndroidFlowQualifier)
    private val backgroundDropController: BackgroundDropController by inject()
    private val presence: AppPresence by inject()
    private val notificationPermission: AndroidNotificationPermission by inject()
    private val attachmentFiles: AndroidAttachmentFiles by inject()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Nothing the app shows is meant to survive a screenshot or the recents thumbnail; debug builds are exempt.
        if (applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE == 0) {
            window.setFlags(WindowManager.LayoutParams.FLAG_SECURE, WindowManager.LayoutParams.FLAG_SECURE)
        }
        authFlowFactory.registerActivity(this)
        privateAuthFlowFactory.registerActivity(this)
        attachmentFiles.register(this)
        enableEdgeToEdge()
        notificationPermission.attach(registerForActivityResult(ActivityResultContracts.RequestPermission()) {})
        openInboxIfAsked(intent)
        setContent { CorvidApp() }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        openInboxIfAsked(intent)
    }

    override fun onDestroy() {
        notificationPermission.attach(null)
        super.onDestroy()
    }

    private fun openInboxIfAsked(intent: Intent) {
        if (intent.getBooleanExtra(EXTRA_OPEN_INBOX, false)) presence.requestInbox()
    }

    // Single-activity app, so this activity's own stop/start is the app's
    // background/foreground signal.
    override fun onStop() {
        super.onStop()
        backgroundDropController.enteredBackground()
        presence.shown(false)
    }

    override fun onStart() {
        super.onStart()
        backgroundDropController.enteredForeground()
        presence.shown(true)
    }
}
