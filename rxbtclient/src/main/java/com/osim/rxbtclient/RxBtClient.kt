package com.osim.rxbtclient

import androidx.annotation.IntRange
import com.osim.rxbtclient.data.BatchProgress
import com.osim.rxbtclient.data.BatchState
import com.polidea.rxandroidble2.RxBleConnection
import com.polidea.rxandroidble2.Timeout
import com.polidea.rxandroidble2.scan.ScanResult
import io.reactivex.rxjava3.core.Observable

interface RxBtClient {

    fun isConnected(): Boolean
    fun scan(): Observable<ScanResult>
    fun createConnection(address: String, timeOut: Timeout? = null, isConnected: (Boolean) -> Unit)
    fun createConnection(address: String, isConnected: (Boolean) -> Unit)
    fun send(bytes: ByteArray)
    fun send(
        bytes: ByteArray,
        @IntRange(
            from = 1,
            to = (RxBleConnection.GATT_MTU_MAXIMUM - RxBleConnection.GATT_WRITE_MTU_OVERHEAD).toLong()
        ) maxBatchSize: Int = 20, buffer: Int = 50
    )

    fun sendBatch(
        bytes: ByteArray,
        @IntRange(
            from = 1,
            to = (RxBleConnection.GATT_MTU_MAXIMUM - RxBleConnection.GATT_WRITE_MTU_OVERHEAD).toLong()
        ) maxBatchSize: Int = 20, buffer: Int = 50
    )
    fun observeBatchProgress(): Observable<BatchProgress>
    fun observeBatchState(): Observable<BatchState>
    fun closeConnection()
    fun read(): Observable<ByteArray>
    fun observeConnection(): Observable<RxBleConnection.RxBleConnectionState>

}