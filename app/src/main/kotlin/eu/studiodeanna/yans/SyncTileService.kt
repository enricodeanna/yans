package eu.studiodeanna.yans

import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import android.widget.Toast

/** "Sync now" in the quick settings: one tap before playing, one after. */
class SyncTileService : TileService() {
    override fun onStartListening() {
        qsTile?.apply {
            state = Tile.STATE_INACTIVE
            updateTile()
        }
    }

    override fun onClick() {
        SyncJobService.syncNow(this)
        Toast.makeText(this, R.string.tile_started, Toast.LENGTH_SHORT).show()
    }
}
