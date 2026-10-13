package com.kesofty.axiapay

import android.app.Application
import dagger.hilt.android.HiltAndroidApp

/** Hosts Hilt's application component; mutable demo sessions remain scoped to their ViewModels. */
@HiltAndroidApp class AxiaPayApplication : Application()
