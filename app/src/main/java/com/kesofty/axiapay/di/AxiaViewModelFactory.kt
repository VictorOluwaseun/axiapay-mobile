package com.kesofty.axiapay.di

import android.net.ConnectivityManager
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.createSavedStateHandle
import androidx.lifecycle.viewmodel.CreationExtras
import com.kesofty.axiapay.data.AndroidInternetConnectionRepository
import com.kesofty.axiapay.presentation.*
import java.time.Clock

/** Supplies Android lifecycle/network adapters to the factory selected by the build flavor. */
class AxiaViewModelFactory(private val clock: Clock) : ViewModelProvider.Factory {
    /**
     * Restores the owner's saved state and delegates feature wiring to the compiled flavor factory.
     */
    override fun <T : ViewModel> create(modelClass: Class<T>, extras: CreationExtras): T {
        require(modelClass == AxiaViewModel::class.java)
        val handle = extras.createSavedStateHandle()
        val application =
            requireNotNull(extras[ViewModelProvider.AndroidViewModelFactory.APPLICATION_KEY])
        val connection =
            AndroidInternetConnectionRepository(
                application.getSystemService(ConnectivityManager::class.java),
            )
        val viewModel = createFlavorViewModel(handle, connection, clock)
        @Suppress("UNCHECKED_CAST")
        return viewModel as T
    }
}
