package com.example.djimichi.dji

import android.content.Context
import android.util.Log
import dji.v5.common.error.IDJIError
import dji.v5.common.register.DJISDKInitEvent
import dji.v5.manager.SDKManager
import dji.v5.manager.interfaces.SDKManagerCallback
import dji.sdk.keyvalue.value.product.ProductType

class DjiSdkManager(private val context: Context) {

    interface Listener {
        fun onRegisterSuccess()
        fun onRegisterFailure(error: IDJIError)
        fun onProductConnected()
        fun onProductDisconnected()
    }

    fun register(listener: Listener) {
        SDKManager.getInstance().init(context, object : SDKManagerCallback {
            override fun onRegisterSuccess() {
                Log.i("DjiSdkManager", "Registro exitoso")
                listener.onRegisterSuccess()
            }

            override fun onRegisterFailure(error: IDJIError) {
                Log.e("DjiSdkManager", "Error de registro: ${error.description()}")
                listener.onRegisterFailure(error)
            }

            override fun onProductConnect(productType: ProductType) {
                Log.i("DjiSdkManager", "Producto conectado: $productType")
                listener.onProductConnected()
            }

            override fun onProductDisconnect(productType: ProductType) {
                Log.i("DjiSdkManager", "Producto desconectado: $productType")
                listener.onProductDisconnected()
            }

            override fun onProductChanged(productType: ProductType) {}
            override fun onInitProcess(event: DJISDKInitEvent, totalProcess: Int) {}
        })
    }

    fun destroy() {
        // SDKManager v5 maneja el ciclo de vida internamente
    }
}
