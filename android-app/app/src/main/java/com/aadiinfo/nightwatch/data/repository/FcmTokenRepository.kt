package com.aadiinfo.nightwatch.data.repository

import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.firestore
import com.google.firebase.Firebase
import kotlinx.coroutines.tasks.await

class FcmTokenRepository(private val firestore: FirebaseFirestore = Firebase.firestore) {
    suspend fun registerToken(uid: String, token: String) {
        firestore.collection("users").document(uid)
            .collection("fcmTokens").document(token)
            .set(mapOf("registeredAt" to System.currentTimeMillis()))
            .await()
    }
}
