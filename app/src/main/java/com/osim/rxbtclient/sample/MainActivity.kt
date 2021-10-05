package com.osim.rxbtclient.sample

import androidx.appcompat.app.AppCompatActivity
import android.os.Bundle
import com.osim.rxbtclient.RxBtClient
import com.polidea.rxandroidble2.RxBleConnection
import dagger.hilt.android.AndroidEntryPoint
import io.reactivex.rxjava3.android.schedulers.AndroidSchedulers
import javax.inject.Inject

@AndroidEntryPoint
class MainActivity : AppCompatActivity() {

    @Inject
    lateinit var btClient: RxBtClient

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        btClient.observeConnection()
            .observeOn(AndroidSchedulers.mainThread())
            .subscribe {
                when(it) {
                    RxBleConnection.RxBleConnectionState.CONNECTED -> {

                    }
                }
            }

        btClient.createConnection("") {

        }

    }
}