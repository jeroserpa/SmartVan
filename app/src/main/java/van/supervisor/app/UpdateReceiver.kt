package van.supervisor.app

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.widget.Toast
import androidx.core.content.IntentCompat

/**
 * Where PackageInstaller reports on an update that [AppUpdate.install]
 * started (D-23). Not exported: the report comes through this app's own
 * PendingIntent.
 */
class UpdateReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        when (val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)) {
            // Android's own "update this app?" screen. The app is on screen -
            // the user tapped Update a moment ago - so it may open it.
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                val confirm = IntentCompat.getParcelableExtra(intent, Intent.EXTRA_INTENT, Intent::class.java)
                    ?: return
                confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                runCatching { context.startActivity(confirm) }
            }
            // This process is about to be replaced; nothing left to do.
            PackageInstaller.STATUS_SUCCESS -> Unit
            // Declined on Android's screen: that is an answer, so do not ask
            // again at the next open.
            PackageInstaller.STATUS_FAILURE_ABORTED -> AppUpdate.snooze(context)
            else -> {
                val why = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE) ?: "status $status"
                Toast.makeText(context, context.getString(R.string.upd_failed, why), Toast.LENGTH_LONG).show()
            }
        }
    }
}
