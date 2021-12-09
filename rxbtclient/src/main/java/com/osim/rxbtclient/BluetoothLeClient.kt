package com.osim.rxbtclient

import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothGattService
import android.content.Context
import androidx.annotation.IntRange
import com.osim.rxbtclient.data.BatchProgress
import com.osim.rxbtclient.data.BatchState
import com.osim.rxbtclient.data.BleConfig
import com.osim.rxbtclient.extension.disposeAndNull
import com.osim.rxbtclient.util.CHexConver
import com.polidea.rxandroidble2.*
import com.polidea.rxandroidble2.scan.ScanResult
import com.polidea.rxandroidble2.scan.ScanSettings
import dagger.hilt.android.qualifiers.ApplicationContext
import hu.akarnokd.rxjava3.bridge.RxJavaBridge
import io.reactivex.rxjava3.core.Completable
import io.reactivex.rxjava3.core.Observable
import io.reactivex.rxjava3.disposables.Disposable
import io.reactivex.rxjava3.subjects.PublishSubject
import timber.log.Timber
import java.util.*
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.roundToInt

@Singleton
class BluetoothLeClient @Inject constructor(
    @ApplicationContext val context: Context,
    val config: BleConfig
) : RxBtClient {

    private var rxBleClient: RxBleClient = RxBleClient.create(context)

    private var rxBleDevice: RxBleDevice? = null
    private var rxBleConnection: RxBleConnection? = null
    private var bluetoothGattService: BluetoothGattService? = null
    private var txCharacteristic: BluetoothGattCharacteristic? = null
    private var rxCharacteristic: BluetoothGattCharacteristic? = null

    private var writeDisposable: Disposable? = null
    private var connectionDisposable: Disposable? = null
    private var connectionStateDisposable: Disposable? = null
    private var stateDisposable: Disposable? = null
    private var notificationDisposable: Disposable? = null
    private var _batchStateObservable = PublishSubject.create<BatchState>()
    private var _batchProgressObservable = PublishSubject.create<BatchProgress>()

    private var _readObservable = PublishSubject.create<ByteArray>()
    private var _connectionStatus = PublishSubject.create<RxBleConnection.RxBleConnectionState>()
    private var _connectionObservable =
        PublishSubject.create<RxBleConnection.RxBleConnectionState>()
    private var _connectCompletable = PublishSubject.create<Completable>()

    init {

        initTimber()

        RxBleClient.updateLogOptions(
            LogOptions.Builder()
                .setLogLevel(if (BuildConfig.DEBUG) LogConstants.VERBOSE else LogConstants.NONE)
                .setMacAddressLogSetting(if (BuildConfig.DEBUG) LogConstants.MAC_ADDRESS_FULL else LogConstants.NONE)
                .setUuidsLogSetting(if(BuildConfig.DEBUG) LogConstants.UUIDS_FULL else LogConstants.NONE)
                .setShouldLogAttributeValues(BuildConfig.DEBUG)
                .build()
        )

    }

    private fun initTimber() =
        if (Timber.treeCount() == 0) Timber.plant(Timber.DebugTree()) else Timber.i("Timber already initialize")

    override fun isConnected(): Boolean =
        rxBleDevice?.connectionState == RxBleConnection.RxBleConnectionState.CONNECTED

    override fun scan(): Observable<ScanResult> = getScanObservable()

    override fun createConnection(
        address: String,
        timeOut: Timeout?,
        isConnected: (Boolean) -> Unit
    ) {
        rxBleDevice = rxBleClient.getBleDevice(address)
        rxBleDevice?.observeConnectionStateChanges()
            ?.subscribe({ state ->
                _connectionObservable.onNext(state)
                when (state!!) {
                    RxBleConnection.RxBleConnectionState.CONNECTING -> {
                        Timber.i("Connecting")
                    }
                    RxBleConnection.RxBleConnectionState.CONNECTED -> {
                        Timber.i("Connected")
                    }
                    RxBleConnection.RxBleConnectionState.DISCONNECTING -> {
                        Timber.i("Disconnecting")
                    }
                    RxBleConnection.RxBleConnectionState.DISCONNECTED -> {
                        Timber.i("Disconnected")
                    }
                }
            }) {
                Timber.i(it)
            }.let { connectionStateDisposable = RxJavaBridge.toV3Disposable(it) }

        fun completeConnection() {
            rxBleDevice?.establishConnection(false)
                ?.flatMapSingle { rxBleConnection ->
                    this.rxBleConnection = rxBleConnection
                    Timber.i("Ready connection")
                    return@flatMapSingle rxBleConnection.requestMtu(512)
                        .map { rxBleConnection }
                }
                ?.flatMapSingle {
                    return@flatMapSingle it.discoverServices()
                }
                ?.flatMapSingle { it.getService(UUID.fromString(config.serviceUUID)) }
                ?.doOnDispose { handleDispose() }
                ?.map {
                    bluetoothGattService = it
                    it
                }?.subscribe({
                    handleBluetoothConnection()
                    isConnected(true)
                }) {
                    Timber.i(it)
                    isConnected(false)
                }.let { connectionDisposable = RxJavaBridge.toV3Disposable(it) }
        }

        timeOut?.let { timeout ->
            var tempScanDisposable: Disposable? = null
            scan()
                .take(timeout.timeout, timeout.timeUnit)
                .filter { it.bleDevice.macAddress == address }
                .doOnError { isConnected(false) }
                .doOnComplete { isConnected(false) }
                .subscribe({
                    completeConnection()
                    tempScanDisposable?.dispose()
                }) {
                    isConnected(false)
                    tempScanDisposable?.dispose()
                }.let {
                    tempScanDisposable = it
                }
        } ?: completeConnection()

    }

    override fun createConnection(address: String, isConnected: (Boolean) -> Unit) = createConnection(address, timeOut = null) {
        isConnected(it)
    }

    override fun send(bytes: ByteArray) {
        Timber.i("Command ${CHexConver.byte2HexStr(bytes)} will be send in a moment")
        txCharacteristic =
            bluetoothGattService?.getCharacteristic(UUID.fromString(config.txCharUUID))
        txCharacteristic?.writeType = BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
        txCharacteristic?.let { characteristic ->
            rxBleConnection?.writeCharacteristic(characteristic, bytes)
                ?.doOnError { writeDisposable = writeDisposable?.disposeAndNull() }
                ?.doOnSuccess { writeDisposable = writeDisposable?.disposeAndNull() }
                ?.subscribe({
                    Timber.i("sent : ${CHexConver.byte2HexStr(it)}")
                }) {
                    Timber.i("send failed : $it")
                }?.let { writeDisposable = RxJavaBridge.toV3Disposable(it) }
        }
    }

    override fun send(
        bytes: ByteArray,
        @IntRange(from = 20, to = 512) maxBatchSize: Int,
        buffer: Int
    ) {

        fun getFinalMtuSize(): Int {
            return rxBleConnection?.mtu
                ?.let {
                    if (it < maxBatchSize) {
                        Timber.i("Provided batch size higher than current MTU size")
                        it
                    } else {
                        maxBatchSize
                    }
                }
                ?: maxBatchSize
        }

        fun progress(page: Int, pages: Int): Int {
            return ((page * 1.0 / pages) * 100).roundToInt()
        }

        _batchStateObservable.onNext(BatchState.DOWNLOAD_START)
        txCharacteristic =
            bluetoothGattService?.getCharacteristic(UUID.fromString(config.txCharUUID))
        txCharacteristic?.writeType = BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
        val packets = bytes.toList().chunked(getFinalMtuSize())
        var downloading = true

        val total = 100
        val pages = packets.size
        var page = 0
        _batchProgressObservable.onNext(BatchProgress(progress(page, pages), total))
        while (downloading) {
            if (page != pages) {
                val packet = packets[page].toByteArray()
                txCharacteristic?.let { characteristic ->
                    rxBleConnection?.writeCharacteristic(characteristic, packet)
                        ?.doOnError { writeDisposable = writeDisposable?.disposeAndNull() }
                        ?.doOnSuccess { writeDisposable = writeDisposable?.disposeAndNull() }
                        ?.subscribe({
                            Timber.i("sent : ${CHexConver.byte2HexStr(it)}")
                            _batchProgressObservable.onNext(
                                BatchProgress(
                                    progress(page, pages),
                                    total
                                )
                            )
                        }) {
                            Timber.i("send failed : $it")
                            downloading = false
                            _batchStateObservable.onNext(BatchState.DOWNLOAD_FAILED)
                        }?.let { writeDisposable = RxJavaBridge.toV3Disposable(it) }
                }
                page += 1
                Thread.sleep(buffer.toLong())
            } else {
                downloading = false
                _batchStateObservable.onNext(BatchState.DOWNLOAD_END)
            }
        }
    }

    override fun sendBatch(
        bytes: ByteArray,
        @IntRange(from = 20, to = 512) maxBatchSize: Int,
        buffer: Int
    ) {

        fun getFinalMtuSize(): Int {
            return rxBleConnection?.mtu
                ?.let {
                    if (it < maxBatchSize) {
                        Timber.i("Provided batch size higher than current MTU size")
                        it
                    } else {
                        maxBatchSize
                    }
                }
                ?: maxBatchSize
        }

        fun progress(page: Int, pages: Int): Int {
            return ((page * 1.0 / pages) * 100).roundToInt()
        }

        _batchStateObservable.onNext(BatchState.DOWNLOAD_START)
        txCharacteristic =
            bluetoothGattService?.getCharacteristic(UUID.fromString(config.txCharUUID))
        txCharacteristic?.writeType = BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE

        val packets = bytes.toList().chunked(getFinalMtuSize())
        val total = 100
        val pages = packets.size
        var page = 0
        _batchProgressObservable.onNext(BatchProgress(progress(page, pages), total))

        txCharacteristic?.let { characteristic ->
            rxBleConnection?.createNewLongWriteBuilder()
                ?.setCharacteristic(characteristic)
                ?.setBytes(bytes)
                ?.setMaxBatchSize(getFinalMtuSize())
                ?.setWriteOperationAckStrategy { bufferNonEmpty ->
                    bufferNonEmpty
                        .delay(buffer.toLong(), TimeUnit.MILLISECONDS)
                        .map { sent ->
                            Timber.i("sent : ${CHexConver.byte2HexStr(packets[page].toByteArray())}")
                            page += 1
                            _batchProgressObservable.onNext(
                                BatchProgress(
                                    progress(page, pages),
                                    total
                                )
                            )
                            sent
                        }
                }
                ?.build()
                ?.doOnComplete {
                    _batchStateObservable.onNext(BatchState.DOWNLOAD_END)
                    writeDisposable = writeDisposable?.disposeAndNull()
                }
                ?.doOnError {
                    _batchStateObservable.onNext(BatchState.DOWNLOAD_FAILED)
                    writeDisposable = writeDisposable?.disposeAndNull()
                }
                ?.subscribe({
                    Timber.i("sent : ${CHexConver.byte2HexStr(it)}")
                }) {
                    Timber.i("send failed : $it")
                }?.let { writeDisposable = RxJavaBridge.toV3Disposable(it) }
        }

    }

    override fun read(): Observable<ByteArray> = _readObservable

    override fun closeConnection() {
        connectionDisposable?.dispose()
        stateDisposable?.dispose()
        notificationDisposable?.dispose()
        writeDisposable?.dispose()
    }

    override fun observeBatchProgress(): Observable<BatchProgress> = _batchProgressObservable

    override fun observeBatchState(): Observable<BatchState> = _batchStateObservable

    override fun observeConnection(): Observable<RxBleConnection.RxBleConnectionState> =
        _connectionStatus

    //private methods

    private fun getScanObservable() = RxJavaBridge.toV3Observable(
        rxBleClient.scanBleDevices(
            ScanSettings.Builder()
                .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
                .build()
        )
    )

    private fun handleBluetoothConnection() {
        rxBleConnection?.writeDescriptor(
            BluetoothGattDescriptor(
                UUID.fromString(config.serviceUUID),
                BluetoothGattDescriptor.PERMISSION_READ
            ), BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
        )
        rxCharacteristic =
            bluetoothGattService?.getCharacteristic(UUID.fromString(config.rxCharUUID))
        rxCharacteristic?.let { characteristics ->
            rxBleConnection?.setupNotification(characteristics)?.flatMap { it }
                ?.subscribe({
                    _readObservable.onNext(it)
                }) {
                    Timber.i(it)
                }
                .let { notificationDisposable = RxJavaBridge.toV3Disposable(it) }
        }
    }

    private fun handleDispose() {
        Timber.i("handleDispose()")
        rxBleDevice = null
        rxBleConnection = null
        bluetoothGattService = null
        txCharacteristic = null
        rxCharacteristic = null
    }

    private fun handleConnectionState() {
        Timber.i("handleConnectionState()")
        rxBleDevice?.observeConnectionStateChanges()
            ?.subscribe({ state ->
                _connectionStatus.onNext(state)
                when (state!!) {
                    RxBleConnection.RxBleConnectionState.CONNECTING -> {
                        Timber.i("Connecting")
                    }
                    RxBleConnection.RxBleConnectionState.CONNECTED -> {
                        Timber.i("Connected")
                    }
                    RxBleConnection.RxBleConnectionState.DISCONNECTING -> {
                        Timber.i("Disconnecting")
                    }
                    RxBleConnection.RxBleConnectionState.DISCONNECTED -> {
                        Timber.i("Disconnected")
                    }
                }
            }) {
                Timber.i(it)
            }.let { stateDisposable = RxJavaBridge.toV3Disposable(it) }
    }

}