package com.aadiinfo.nightwatch.data.repository

import com.google.firebase.firestore.DocumentReference
import com.google.firebase.firestore.DocumentSnapshot
import com.google.firebase.firestore.Query
import com.google.firebase.firestore.QuerySnapshot
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.catch

/** Listener errors (permission-denied, transient network issues) are logged
 * and end the flow rather than crashing the app - a screen that's mid-listen
 * when access changes or the network hiccups shouldn't take the whole app down. */
fun DocumentReference.snapshotFlow(): Flow<DocumentSnapshot?> = callbackFlow {
    val registration = addSnapshotListener { snapshot, error ->
        if (error != null) {
            close(error)
            return@addSnapshotListener
        }
        trySend(snapshot)
    }
    awaitClose { registration.remove() }
}.catch { android.util.Log.e("FirestoreExt", "DocumentReference listener failed", it) }

fun Query.snapshotFlow(): Flow<QuerySnapshot> = callbackFlow {
    val registration = addSnapshotListener { snapshot, error ->
        if (error != null) {
            close(error)
            return@addSnapshotListener
        }
        if (snapshot != null) trySend(snapshot)
    }
    awaitClose { registration.remove() }
}.catch { android.util.Log.e("FirestoreExt", "Query listener failed", it) }
