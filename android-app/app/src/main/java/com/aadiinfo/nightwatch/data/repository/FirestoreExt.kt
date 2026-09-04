package com.aadiinfo.nightwatch.data.repository

import com.google.firebase.firestore.DocumentReference
import com.google.firebase.firestore.DocumentSnapshot
import com.google.firebase.firestore.Query
import com.google.firebase.firestore.QuerySnapshot
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.retryWhen
import kotlin.math.min
import kotlin.math.pow

// Every ViewModel using these listeners shares one instance for the whole
// app session (SharingStarted.Lazily, chosen to stop re-paying a full
// range-query read on every tab switch) - which means an upstream flow that
// just logs an error and ends, as this used to, would leave that listener
// permanently dead for the rest of the process's life the first time it hit
// any error at all: a permission change, a network blip, or - as actually
// happened - a deliberate rules lockdown during an incident. Retrying with
// backoff instead means any transient failure heals itself the moment
// whatever caused it clears, instead of silently freezing the screen until
// someone thinks to force-quit the app.
private const val RETRY_BASE_DELAY_MS = 2_000L
private const val RETRY_MAX_DELAY_MS = 60_000L

private fun retryDelayFor(attempt: Long): Long =
    min(RETRY_BASE_DELAY_MS * 2.0.pow(attempt.toInt()).toLong(), RETRY_MAX_DELAY_MS)

fun DocumentReference.snapshotFlow(): Flow<DocumentSnapshot?> = callbackFlow {
    val registration = addSnapshotListener { snapshot, error ->
        if (error != null) {
            close(error)
            return@addSnapshotListener
        }
        trySend(snapshot)
    }
    awaitClose { registration.remove() }
}.retryWhen { cause, attempt ->
    android.util.Log.e("FirestoreExt", "DocumentReference listener failed, retrying", cause)
    delay(retryDelayFor(attempt))
    true
}

fun Query.snapshotFlow(): Flow<QuerySnapshot> = callbackFlow {
    val registration = addSnapshotListener { snapshot, error ->
        if (error != null) {
            close(error)
            return@addSnapshotListener
        }
        if (snapshot != null) trySend(snapshot)
    }
    awaitClose { registration.remove() }
}.retryWhen { cause, attempt ->
    android.util.Log.e("FirestoreExt", "Query listener failed, retrying", cause)
    delay(retryDelayFor(attempt))
    true
}
