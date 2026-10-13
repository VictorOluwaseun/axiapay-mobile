package com.kesofty.axiapay.di

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.createSavedStateHandle
import androidx.lifecycle.viewmodel.CreationExtras
import com.kesofty.axiapay.application.CompleteDemoAccess
import com.kesofty.axiapay.application.PrepareDemoAccess
import com.kesofty.axiapay.presentation.auth.AuthViewModel

/** Supplies only the flavor-selected auth preview adapter; it shares no mutable payment state. */
class AuthViewModelFactory : ViewModelProvider.Factory {
    /** Constructs an account-entry owner with the Activity's Android saved-state lifecycle. */
    override fun <T : ViewModel> create(modelClass: Class<T>, extras: CreationExtras): T {
        require(modelClass == AuthViewModel::class.java)
        val repository = createFlavorAccessRepository()
        @Suppress("UNCHECKED_CAST")
        return AuthViewModel(
            extras.createSavedStateHandle(),
            PrepareDemoAccess(repository),
            CompleteDemoAccess(repository),
        )
            as T
    }
}
