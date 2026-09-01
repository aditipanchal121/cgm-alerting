package com.aadiinfo.nightwatch.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory

/** Small helper so each screen can build its ViewModel from AppContainer
 * without pulling in a DI framework. */
fun <T : ViewModel> vmFactory(create: () -> T): ViewModelProvider.Factory =
    viewModelFactory { initializer { create() } }
