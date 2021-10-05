package com.osim.rxbtclient.sample

import android.app.Application
import dagger.hilt.android.HiltAndroidApp
import timber.log.Timber

@HiltAndroidApp
class RxBtClientApplication : Application() {

    override fun onCreate() {
        super.onCreate()

        initTimber()

    }

    private fun initTimber() =
        if (Timber.treeCount() == 0) Timber.plant(Timber.DebugTree()) else Timber.i("Timber already initialize")
}