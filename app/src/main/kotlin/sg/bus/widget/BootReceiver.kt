package sg.bus.widget

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** Reboot / app update: any live session died with the process, so draw every widget idle (and re-attach its tap). */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        for (id in Widgets.ids(ctx)) Store.saveState(ctx, id, Store.state(ctx, id).copy(sessionEndMs = 0))
        Widgets.renderAll(ctx)
    }
}
