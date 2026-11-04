package com.thermalguard.app.data

import com.google.firebase.database.DataSnapshot
import com.google.firebase.database.DatabaseError
import com.google.firebase.database.FirebaseDatabase
import com.google.firebase.database.ValueEventListener

/**
 * Firebase server time, used for every "how old is this heartbeat/reading" check.
 *
 * The phone clock and the Raspberry Pi clock can both be wrong (the Pi has no
 * RTC, phones drift or are set by hand). Comparing one with the other made the
 * app treat a healthy Pi as offline and suppress alarms. Both sides now use the
 * Firebase server clock: the Pi stamps `controller/server_seen` with a server
 * timestamp, and the app reads `.info/serverTimeOffset` to know server time.
 */
object ServerClock {
    @Volatile
    var offsetMs: Long = 0L
        private set

    @Volatile
    private var started = false

    /** Current Firebase server time in epoch milliseconds (phone time if never connected). */
    fun now(): Long = System.currentTimeMillis() + offsetMs

    fun start(database: FirebaseDatabase) {
        if (started) return
        started = true
        database.getReference(".info/serverTimeOffset")
            .addValueEventListener(object : ValueEventListener {
                override fun onDataChange(snapshot: DataSnapshot) {
                    (snapshot.value as? Number)?.let { offsetMs = it.toLong() }
                }

                override fun onCancelled(error: DatabaseError) = Unit
            })
    }
}
