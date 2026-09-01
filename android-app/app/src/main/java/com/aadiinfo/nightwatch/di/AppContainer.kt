package com.aadiinfo.nightwatch.di

import com.aadiinfo.nightwatch.data.repository.AuthRepository
import com.aadiinfo.nightwatch.data.repository.FcmTokenRepository
import com.aadiinfo.nightwatch.data.repository.PatientRepository

/**
 * Manual dependency container. Kept deliberately simple (no Hilt) since
 * this is a base prototype with three repositories - reach for a DI
 * framework only if that stops being true.
 */
class AppContainer {
    val authRepository = AuthRepository()
    val patientRepository = PatientRepository()
    val fcmTokenRepository = FcmTokenRepository()
}
