package com.makefunstuff.herdr.share

import android.annotation.SuppressLint
import android.app.PendingIntent
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import com.makefunstuff.herdr.HerdrApp
import com.makefunstuff.herdr.MainActivity
import com.makefunstuff.herdr.ui.share.Ask

/**
 * The Quick Settings tile: a tap opens the Coordinator chat at once (the one used last, or asks
 * which), after unlocking. Under its name it says what needs you, or the machine it opens.
 */
class CoordinatorTile : TileService() {
    override fun onStartListening() {
        val tile = qsTile ?: return
        val container = (application as HerdrApp).container
        container.tally.recount()
        val counts = container.tally.counts.value
        tile.state = Tile.STATE_INACTIVE
        if (Build.VERSION.SDK_INT >= 29) {
            tile.subtitle = if (counts.known) counts.words else container.shares.last.value?.let { container.machines.get(it)?.label }
        }
        tile.updateTile()
    }

    override fun onClick() {
        if (isLocked) unlockAndRun(::open) else open()
    }

    @SuppressLint("StartActivityAndCollapseDeprecated")
    private fun open() {
        val intent = MainActivity.ask(this, Ask.COORDINATOR)
        if (Build.VERSION.SDK_INT >= 34) {
            startActivityAndCollapse(PendingIntent.getActivity(this, 0, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT))
        } else {
            @Suppress("DEPRECATION")
            startActivityAndCollapse(intent)
        }
    }
}
