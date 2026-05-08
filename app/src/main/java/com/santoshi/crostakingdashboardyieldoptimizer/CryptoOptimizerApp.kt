package com.santoshi.crostakingdashboardyieldoptimizer

import android.app.Application
import com.reown.android.Core
import com.reown.android.CoreClient
import com.reown.android.relay.ConnectionType
import com.reown.sign.client.Sign
import com.reown.sign.client.SignClient

class CryptoOptimizerApp : Application() {
    override fun onCreate() {
        super.onCreate()

        val appMetaData = Core.Model.AppMetaData(
            name = "Cronos (CRO) Staking Dashboard and Yield Optimizer",
            description = "Native Staking and Yield Optimization for Cronos POS",
            url = "https://lotto-cro.tedcrypto.io/",
            icons = listOf("https://lotto-cro.tedcrypto.io/validator-logo.png"),
            redirect = "kotlin-wallet-wc:/request"
        )

        CoreClient.initialize(
            relayServerUrl = "wss://relay.walletconnect.com?projectId=2b84ffe3351d1baf2dc450a49b224e74",
            connectionType = ConnectionType.AUTOMATIC,
            application = this,
            metaData = appMetaData
        ) { error -> }

        val initParams = Sign.Params.Init(core = CoreClient)
        SignClient.initialize(initParams) { error -> }
    }
}