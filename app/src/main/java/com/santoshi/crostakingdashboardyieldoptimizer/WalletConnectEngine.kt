package com.santoshi.crostakingdashboardyieldoptimizer

import com.reown.sign.client.Sign
import com.reown.sign.client.SignClient
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow

class WalletConnectEngine {

    private val _walletState = MutableStateFlow<WalletState>(WalletState.Disconnected)
    val walletState: StateFlow<WalletState> = _walletState.asStateFlow()

    // VERIFIABLE FIX: One-Time Event Pipeline for Toasts and UI unlocking
    private val _walletEvents = kotlinx.coroutines.flow.MutableSharedFlow<WalletEvent>(extraBufferCapacity = 1)
    val walletEvents = _walletEvents.asSharedFlow()

    private var currentSessionTopic: String? = null

    // VERIFIABLE FIX: Elevate the delegate to a class-level property.
    // This mathematically prevents Android's Garbage Collector from silently destroying the WebSocket listener.
    private val walletDelegate = object : SignClient.DappDelegate {
        override fun onSessionApproved(approvedSession: Sign.Model.ApprovedSession) {
            currentSessionTopic = approvedSession.topic
            val accounts = approvedSession.namespaces["cosmos"]?.accounts ?: emptyList()

            val croAddress = accounts.firstOrNull { it.contains("crypto-org-chain-mainnet-1") }
                ?.split(":")?.last()

            if (croAddress != null) {
                _walletState.value = WalletState.Connected(croAddress)
            } else {
                _walletState.value = WalletState.Error("No valid Cronos POS address returned.")
            }
        }

        override fun onSessionRejected(rejectedSession: Sign.Model.RejectedSession) {
            _walletState.value = WalletState.Disconnected
        }

        override fun onSessionUpdate(updatedSession: Sign.Model.UpdatedSession) {}
        override fun onSessionEvent(sessionEvent: Sign.Model.SessionEvent) {}

        override fun onSessionDelete(deletedSession: Sign.Model.DeletedSession) {
            // VERIFIABLE FIX: Property-agnostic validation.
            // We mathematically query the database directly to see if our session survived.
            val activeSessions = SignClient.getListOfActiveSessions()
            val isOurSessionAlive = activeSessions.any { it.topic == currentSessionTopic }

            if (!isOurSessionAlive || currentSessionTopic == null) {
                currentSessionTopic = null
                _walletState.value = WalletState.Disconnected
            }
        }

        override fun onSessionExtend(session: Sign.Model.Session) {}

        override fun onSessionRequestResponse(response: Sign.Model.SessionRequestResponse) {
            if (response.result is Sign.Model.JsonRpcResponse.JsonRpcResult) {
                val resultData = (response.result as Sign.Model.JsonRpcResponse.JsonRpcResult).result.toString()
                _walletState.value = WalletState.SignatureReceived(resultData)
            } else if (response.result is Sign.Model.JsonRpcResponse.JsonRpcError) {
                // VERIFIABLE FIX: Shoot the error through the one-time event pipeline
                val errorData = (response.result as Sign.Model.JsonRpcResponse.JsonRpcError).message
                _walletEvents.tryEmit(WalletEvent.TransactionRejected(errorData))
                restoreActiveSession()
            }
        }

        override fun onConnectionStateChange(state: Sign.Model.ConnectionState) {}
        override fun onError(error: Sign.Model.Error) {}
        override fun onProposalExpired(proposal: Sign.Model.ExpiredProposal) {}
        override fun onRequestExpired(request: Sign.Model.ExpiredRequest) {}
    }

    init {
        // The SignClient now holds a reference to an immortal class property
        SignClient.setDappDelegate(walletDelegate)
        restoreActiveSession()
    }

    fun connect(onUriGenerated: (String) -> Unit) {
        val namespace = Sign.Model.Namespace.Proposal(
            chains = listOf("cosmos:crypto-org-chain-mainnet-1"),
            methods = listOf("cosmos_signDirect", "cosmos_signAmino"),
            events = listOf("chainChanged", "accountsChanged")
        )

        val pairing = com.reown.android.CoreClient.Pairing.create()
        if (pairing == null) {
            _walletState.value = WalletState.Error("Failed to generate pairing. Relay disconnected.")
            return
        }

        val connectParams = Sign.Params.Connect(
            namespaces = mapOf("cosmos" to namespace),
            pairing = pairing!!
        )

        SignClient.connect(connectParams,
            onSuccess = { _: String ->
                onUriGenerated(pairing.uri)
            },
            onError = { error: Sign.Model.Error ->
                _walletState.value = WalletState.Error(error.throwable.message ?: "Unknown Connection Error")
            }
        )
    }

    private fun restoreActiveSession() {
        try {
            val activeSessions = SignClient.getListOfActiveSessions()
            if (activeSessions.isNotEmpty()) {
                val session = activeSessions.last()
                currentSessionTopic = session.topic

                val accounts = session.namespaces["cosmos"]?.accounts
                val fullAccountString = accounts?.firstOrNull { it.contains("crypto-org-chain-mainnet-1") }

                if (fullAccountString != null) {
                    val address = fullAccountString.substringAfterLast(":")
                    _walletState.value = WalletState.Connected(address)
                }
            }
        } catch (e: Exception) {
            _walletState.value = WalletState.Disconnected
        }
    }

    fun sendCosmosTransaction(
        delegatorAddress: String,
        messagesJsonArray: String,
        memo: String,
        accountNumber: String,
        sequence: String,
        onSuccess: () -> Unit,
        onError: (String) -> Unit
    ) {
        val topic = currentSessionTopic
        if (topic == null) {
            onError("No active wallet session. Please connect your wallet.")
            return
        }

        try {
            val msgsArray = com.google.gson.JsonParser.parseString(messagesJsonArray).asJsonArray

            // Scale gas based on message count and type
            val messageCount = msgsArray.size().coerceAtLeast(1)
            val hasRedelegate = msgsArray.any {
                (it.asJsonObject.get("type")?.asString ?: "").contains("Redelegate")
            }
            val perMsgGas = if (hasRedelegate) 300000L else 150000L
            val gasLimit = 150000L + (messageCount * perMsgGas)
            val feeAmount = maxOf(25000L, gasLimit / 10)

            val feeAmountCoin = com.google.gson.JsonObject().apply {
                addProperty("amount", feeAmount.toString())
                addProperty("denom", "basecro")
            }
            val feeAmountArray = com.google.gson.JsonArray().apply { add(feeAmountCoin) }
            val feeObj = com.google.gson.JsonObject().apply {
                add("amount", feeAmountArray)
                addProperty("gas", gasLimit.toString())
            }

            val signDocObj = com.google.gson.JsonObject().apply {
                addProperty("account_number", accountNumber)
                addProperty("chain_id", "crypto-org-chain-mainnet-1")
                add("fee", feeObj)
                addProperty("memo", memo)
                add("msgs", msgsArray)
                addProperty("sequence", sequence)
            }

            val paramsObj = com.google.gson.JsonObject().apply {
                addProperty("signerAddress", delegatorAddress)
                add("signDoc", signDocObj)
            }

            val gson = com.google.gson.GsonBuilder().disableHtmlEscaping().create()
            val paramsJson = gson.toJson(paramsObj)

            val request = Sign.Params.Request(
                sessionTopic = topic,
                method = "cosmos_signAmino",
                params = paramsJson,
                chainId = "cosmos:crypto-org-chain-mainnet-1"
            )

            SignClient.request(request,
                onSuccess = { onSuccess() },
                onError = { error: Sign.Model.Error ->
                    onError(error.throwable.message ?: "Failed to send request to wallet.")
                }
            )
        } catch (e: Exception) {
            onError("JSON Serialization Error: ${e.message}")
        }
    }

    fun disconnect() {
        currentSessionTopic?.let { topic ->
            val disconnectParams = Sign.Params.Disconnect(topic)
            SignClient.disconnect(disconnectParams) { error: Sign.Model.Error ->
                _walletState.value = WalletState.Error(error.throwable.message ?: "Failed to disconnect")
            }
        }
        currentSessionTopic = null
        _walletState.value = WalletState.Disconnected
    }
}

sealed class WalletState {
    object Disconnected : WalletState()
    data class Connected(val address: String) : WalletState()
    data class Error(val message: String) : WalletState()
    data class SignatureReceived(val signaturePayload: String) : WalletState()
}

sealed class WalletEvent {
    data class TransactionRejected(val message: String) : WalletEvent()
}