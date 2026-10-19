package com.kesofty.axiapay.di

import javax.inject.Qualifier

/** Raw transport for credential exchange; resource requests use the authorized HTTP binding. */
@Qualifier @Retention(AnnotationRetention.BINARY) internal annotation class AuthenticationTransport
