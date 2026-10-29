package eu.studiodeanna.yans.sync

import kotlin.test.Test

/**
 * Serves the fake Nextcloud on port 8899 for 30 minutes, to try the app on the emulator against it:
 *
 *     YANS_SERVE=1 ./gradlew :core:test --tests '*ServeFakeNextcloud*'
 *
 * In the app (a debug build), log in with an app password: address http://10.0.2.2:8899/nextcloud, user
 * enrico@example.org, password app-pass. Without YANS_SERVE the test does nothing.
 */
class ServeFakeNextcloud {
    @Test
    fun serve() {
        if (System.getenv("YANS_SERVE") == null) return
        val nextcloud = FakeNextcloud()
        nextcloud.put("ScummVM Saves/monkey2.001", "a save made on the computer", 1_793_000_000_000)
        nextcloud.server.start(8899)
        Thread.sleep(30 * 60_000)
        nextcloud.server.shutdown()
    }
}
