package com.osim.rxbtclient.extension

import io.reactivex.rxjava3.disposables.Disposable

fun Disposable.disposeAndNull(): Disposable? {
    dispose()
    return null
}