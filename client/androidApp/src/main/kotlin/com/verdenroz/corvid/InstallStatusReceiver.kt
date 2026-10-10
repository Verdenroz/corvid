package com.verdenroz.corvid

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import androidx.core.content.IntentCompat
import com.verdenroz.corvid.core.data.InstallResult
import kotlinx.coroutines.CompletableDeferred
import java.util.concurrent.ConcurrentHashMap

/** Install sessions waiting on the system's verdict, by session id. */
internal object InstallSessions {
    private val waiting = ConcurrentHashMap<Int, CompletableDeferred<InstallResult>>()

    fun expect(id: Int) = CompletableDeferred<InstallResult>().also { waiting[id] = it }

    fun forget(id: Int) = waiting.remove(id)

    fun finish(
        id: Int,
        result: InstallResult,
    ) = waiting.remove(id)?.complete(result)
}

class InstallStatusReceiver : BroadcastReceiver() {
    override fun onReceive(
        context: Context,
        intent: Intent,
    ) {
        val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)
        if (status == PackageInstaller.STATUS_PENDING_USER_ACTION) {
            val confirm = IntentCompat.getParcelableExtra(intent, Intent.EXTRA_INTENT, Intent::class.java) ?: return
            context.startActivity(confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            return
        }
        val id = intent.getIntExtra(PackageInstaller.EXTRA_SESSION_ID, -1)
        InstallSessions.finish(id, installResult(status, intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE)))
    }
}
