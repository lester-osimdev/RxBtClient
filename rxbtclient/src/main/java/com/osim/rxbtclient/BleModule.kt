package com.osim.rxbtclient

import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

@Module
@InstallIn(SingletonComponent::class)
abstract class BleModule {

    @Binds
    abstract fun bindRxBleClient(bleClient: BluetoothLeClient): RxBtClient

}