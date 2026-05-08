package com.santoshi.crostakingdashboardyieldoptimizer

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

object CryptoEngine {
    val client = OkHttpClient.Builder()
        .connectTimeout(60, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        .build()

    private val gson = com.google.gson.GsonBuilder().setLenient().create()

    private val nodeUrl = "https://rest.mainnet.crypto.org"
    private val indexerUrl = "https://cronos-pos.org/explorer/api/v1"
    private val divisor = 100_000_000.0

    private val baseCroRegex = Regex("""(\d+)basecro""")

    private fun fetchJson(url: String): JsonObject? {
        val req = Request.Builder().url(url).build()
        return try {
            client.newCall(req).execute().use { response ->
                if (response.isSuccessful) {
                    JsonParser.parseString(response.body?.string() ?: "").asJsonObject
                } else null
            }
        } catch (e: Exception) { null }
    }

    suspend fun getBondedBalance(address: String): Double = withContext(Dispatchers.IO) {
        val json = fetchJson("$nodeUrl/cosmos/staking/v1beta1/delegations/$address") ?: return@withContext 0.0
        var total = 0.0
        json.getAsJsonArray("delegation_responses")?.forEach {
            val balance = it.asJsonObject.getAsJsonObject("balance")
            if (balance?.get("denom")?.asString == "basecro") {
                total += balance.get("amount").asString.toDoubleOrNull() ?: 0.0
            }
        }
        return@withContext total / divisor
    }

    suspend fun getUnclaimedRewards(address: String): Double = withContext(Dispatchers.IO) {
        val json = fetchJson("$nodeUrl/cosmos/distribution/v1beta1/delegators/$address/rewards") ?: return@withContext 0.0
        var totalRewards = 0.0
        json.getAsJsonArray("total")?.forEach {
            val reward = it.asJsonObject
            if (reward.get("denom").asString == "basecro") {
                totalRewards += reward.get("amount").asString.toDoubleOrNull() ?: 0.0
            }
        }
        return@withContext totalRewards / divisor
    }

    suspend fun getAvailableBalance(address: String): Double = withContext(Dispatchers.IO) {
        val json = fetchJson("$indexerUrl/accounts/$address") ?: return@withContext 0.0
        val balanceArray = json.getAsJsonObject("result")?.getAsJsonArray("balance")
        val baseCroObj = balanceArray?.firstOrNull { it.asJsonObject.get("denom").asString == "basecro" }
        return@withContext (baseCroObj?.asJsonObject?.get("amount")?.asString?.toDoubleOrNull() ?: 0.0) / divisor
    }

    suspend fun getOtherDelegations(address: String, excludeValidator: String): List<Pair<String, Long>> = withContext(Dispatchers.IO) {
        try {
            val json = fetchJson("$nodeUrl/cosmos/staking/v1beta1/delegations/$address") ?: return@withContext emptyList()
            val list = mutableListOf<Pair<String, Long>>()
            json.getAsJsonArray("delegation_responses")?.forEach {
                val del = it.asJsonObject.getAsJsonObject("delegation")
                val valAddr = del?.get("validator_address")?.asString ?: ""
                val balance = it.asJsonObject.getAsJsonObject("balance")
                val denom = balance?.get("denom")?.asString
                val amount = balance?.get("amount")?.asString?.toLongOrNull() ?: 0L
                if (denom == "basecro" && valAddr.isNotEmpty() && valAddr != excludeValidator && amount > 0L) {
                    list.add(Pair(valAddr, amount))
                }
            }
            return@withContext list
        } catch (e: Exception) {
            return@withContext emptyList()
        }
    }

    suspend fun getClaimedRewards(address: String): Double = withContext(Dispatchers.IO) {
        return@withContext 0.0
    }

    suspend fun getCroPrice(currency: String): Double = withContext(Dispatchers.IO) {
        val json = fetchJson("https://api.coingecko.com/api/v3/simple/price?ids=crypto-com-chain&vs_currencies=$currency")
        return@withContext json?.getAsJsonObject("crypto-com-chain")?.get(currency.lowercase())?.asDouble ?: 0.0
    }

    // VERIFIABLE FIX: Bulletproof Cosmos SDK Network APR Calculation
    suspend fun getLiveNetworkAPR(): Double {
        return kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            try {
                val inflationRes = java.net.URL("https://rest.mainnet.crypto.org/cosmos/mint/v1beta1/inflation").readText()
                val inflation = com.google.gson.JsonParser.parseString(inflationRes).getAsJsonObject().get("inflation").getAsString().toDouble()

                val distRes = java.net.URL("https://rest.mainnet.crypto.org/cosmos/distribution/v1beta1/params").readText()
                val communityTax = com.google.gson.JsonParser.parseString(distRes).getAsJsonObject().get("params").getAsJsonObject().get("community_tax").getAsString().toDouble()

                val poolRes = java.net.URL("https://rest.mainnet.crypto.org/cosmos/staking/v1beta1/pool").readText()
                val bondedTokens = com.google.gson.JsonParser.parseString(poolRes).getAsJsonObject().get("pool").getAsJsonObject().get("bonded_tokens").getAsString().toDouble()

                // Try both Cosmos SDK endpoints for supply to avoid crashes
                var totalSupply = 0.0
                try {
                    val supplyRes = java.net.URL("https://rest.mainnet.crypto.org/cosmos/bank/v1beta1/supply/by_denom?denom=basecro").readText()
                    totalSupply = com.google.gson.JsonParser.parseString(supplyRes).getAsJsonObject().get("amount").getAsJsonObject().get("amount").getAsString().toDouble()
                } catch (e: Exception) {
                    val supplyRes = java.net.URL("https://rest.mainnet.crypto.org/cosmos/bank/v1beta1/supply/basecro").readText()
                    totalSupply = com.google.gson.JsonParser.parseString(supplyRes).getAsJsonObject().get("amount").getAsJsonObject().get("amount").getAsString().toDouble()
                }

                if (totalSupply == 0.0) return@withContext 0.0

                val bondingRatio = bondedTokens / totalSupply
                val trueApr = (inflation * (1.0 - communityTax)) / bondingRatio

                return@withContext if (trueApr > 0) trueApr * 100.0 else 0.0
            } catch (e: Exception) {
                // If the node drops the connection, fail gracefully
                return@withContext 0.0
            }
        }
    }

    private fun parseAmount(raw: String): Double {
        return try {
            val match = baseCroRegex.find(raw)
            match?.groupValues?.get(1)?.toDoubleOrNull() ?: 0.0
        } catch (e: Exception) { 0.0 }
    }

    private fun extractAmount(data: JsonObject, key: String): Double {
        if (!data.has(key)) return 0.0
        val element = data.get(key)
        var total = 0.0
        try {
            if (element.isJsonPrimitive && element.asJsonPrimitive.isString) {
                val str = element.asString
                val match = baseCroRegex.find(str)
                if (match != null) {
                    total += match.groupValues[1].toDoubleOrNull() ?: 0.0
                }
            } else if (element.isJsonArray) {
                element.asJsonArray.forEach {
                    val item = it.asJsonObject
                    if (item.get("denom")?.asString == "basecro") {
                        total += item.get("amount")?.asString?.toDoubleOrNull() ?: 0.0
                    }
                }
            } else if (element.isJsonObject) {
                val item = element.asJsonObject
                if (item.get("denom")?.asString == "basecro") {
                    total += item.get("amount")?.asString?.toDoubleOrNull() ?: 0.0
                }
            }
        } catch (e: Exception) {}
        return total
    }

    fun com.google.gson.JsonObject.safeGetAsJsonArray(key: String): com.google.gson.JsonArray? {
        val el = this.get(key) ?: return null
        return if (el.isJsonArray) el.asJsonArray else null
    }

    suspend fun syncAllTimeRewards(
        address: String,
        savedHash: String?,
        savedTotal: Double,
        savedBreakdown: Map<String, Double>?,
        isCancelled: () -> Boolean,
        onSaveState: (Double, String, Map<String, Double>) -> Unit,
        onProgress: (Int, Int) -> Unit,
        onBreakdown: (Map<String, Double>) -> Unit = {}
    ): Double = withContext(Dispatchers.IO) {
        try {
            withContext(Dispatchers.Main) { onProgress(0, 0) }
            android.util.Log.d("SYNC_SRC", "Sync start: savedHash=$savedHash savedTotal=$savedTotal")
            var runningTotalBaseCro = 0.0
            val limit = 100
            var newestHashThisSession: String? = null
            var newestBlockHeightThisSession = 0
            val processedHashes = mutableSetOf<String>()

            var manualClaims = savedBreakdown?.get("Manual Claims") ?: 0.0
            var restakeCompounds = savedBreakdown?.get("Auto-Restake") ?: 0.0
            var validatorCommissions = savedBreakdown?.get("Validator Commissions") ?: 0.0
            var autoClaims = savedBreakdown?.get("Staking Auto-Claims") ?: 0.0

            val safeAddress = address.trim().lowercase()

            var savedBlockHeight = 0
            if (!savedHash.isNullOrEmpty()) {
                try {
                    val hashReq = okhttp3.Request.Builder().url("$indexerUrl/transactions/$savedHash").build()
                    val hashRes = client.newCall(hashReq).execute()
                    if (hashRes.isSuccessful) {
                        val root = com.google.gson.JsonParser.parseString(hashRes.body?.string() ?: "{}").asJsonObject
                        savedBlockHeight = root.getAsJsonObject("result")?.get("blockHeight")?.asInt ?: 0
                    }
                    hashRes.close()
                } catch (e: Exception) {}
            }

            // =========================================================================
            // SOURCE 1: REST API - coin_received.receiver (DESC order)
            // =========================================================================
            var s1Page = 1
            var s1Total = 1
            var s1Finished = false
            var retryCount = 0

            while (isActive && !s1Finished && !isCancelled()) {
                try {
                    val query = "coin_received.receiver%3D%27$address%27"
                    val urlStr = "$nodeUrl/cosmos/tx/v1beta1/txs?query=$query&limit=$limit&page=$s1Page&order_by=ORDER_BY_DESC"
                    val req = okhttp3.Request.Builder().url(urlStr).header("User-Agent", "Mozilla/5.0").build()
                    val res = client.newCall(req).execute()

                    if (!res.isSuccessful) {
                        res.close()
                        if (retryCount < 2) { retryCount++; kotlinx.coroutines.delay(1000); continue }
                        else { s1Finished = true; break }
                    }
                    retryCount = 0

                    val jsonStr = res.body?.string() ?: ""
                    res.close()
                    val root = com.google.gson.JsonParser.parseString(jsonStr).asJsonObject
                    val txs = root.getAsJsonArray("tx_responses")

                    if (txs == null || txs.size() == 0) { s1Finished = true; continue }

                    val newTotal = if (txs.size() == limit) s1Page + 1 else s1Page
                    if (newTotal > s1Total) s1Total = newTotal

                    for (i in 0 until txs.size()) {
                        try {
                            val tx = txs.get(i).asJsonObject
                            val hash = tx.get("txhash")?.asString ?: ""
                            val hgt = tx.get("height")?.asString?.toIntOrNull() ?: 0

                            if (hgt > newestBlockHeightThisSession) {
                                newestBlockHeightThisSession = hgt
                                newestHashThisSession = hash
                            }
                            if (processedHashes.contains(hash)) continue
                            if (savedBlockHeight > 0 && hgt <= savedBlockHeight) { s1Finished = true; break }

                            var authzCompoundRaw = 0.0
                            var txManualClaimRaw = 0.0
                            var txCommissionRaw = 0.0
                            var txShiftRaw = 0.0

                            var isExplicitClaim = false
                            var isShiftAction = false
                            var isManualStakingAction = false
                            var isBotCompound = false

                            try {
                                val messages = tx.getAsJsonObject("tx")?.getAsJsonObject("body")?.getAsJsonArray("messages")
                                if (messages != null && messages.size() > 0) {
                                    messages.forEach { msg ->
                                        val type = msg.asJsonObject.get("@type")?.asString ?: ""
                                        if (type.contains("MsgExec") || type.contains("authz")) {
                                            isBotCompound = true
                                            msg.asJsonObject.getAsJsonArray("msgs")?.forEach { inner ->
                                                val innerType = inner.asJsonObject.get("@type")?.asString ?: ""
                                                if (innerType == "/cosmos.staking.v1beta1.MsgDelegate") {
                                                    if (inner.asJsonObject.get("delegator_address")?.asString
                                                            ?.trim()?.lowercase() == safeAddress) {
                                                        authzCompoundRaw += inner.asJsonObject
                                                            .getAsJsonObject("amount")
                                                            ?.get("amount")?.asString
                                                            ?.toDoubleOrNull() ?: 0.0
                                                    }
                                                }
                                            }
                                        } else if (type.contains("MsgWithdrawDelegatorReward")) {
                                            if (msg.asJsonObject.get("delegator_address")?.asString
                                                    ?.trim()?.lowercase() == safeAddress) {
                                                isManualStakingAction = true; isExplicitClaim = true
                                            }
                                        } else if (type.contains("MsgDelegate") || type.contains("MsgUndelegate") ||
                                            type.contains("MsgBeginRedelegate")) {
                                            if (msg.asJsonObject.get("delegator_address")?.asString
                                                    ?.trim()?.lowercase() == safeAddress) {
                                                isManualStakingAction = true; isShiftAction = true
                                            }
                                        } else if (type.contains("MsgWithdrawValidatorCommission")) {
                                            isManualStakingAction = true
                                        }
                                    }
                                }
                            } catch (e: Exception) {}

                            if (isManualStakingAction) {
                                val allEvents = mutableListOf<com.google.gson.JsonObject>()
                                val logs = tx.getAsJsonArray("logs")
                                if (logs != null && logs.size() > 0) {
                                    logs.forEach { log ->
                                        log.asJsonObject.safeGetAsJsonArray("events")
                                            ?.forEach { allEvents.add(it.asJsonObject) }
                                    }
                                } else {
                                    tx.safeGetAsJsonArray("events")?.forEach { allEvents.add(it.asJsonObject) }
                                }

                                allEvents.forEach { ev ->
                                    val et = ev.get("type")?.asString ?: ""
                                    if (et == "withdraw_rewards") {
                                        var delegatorInEvent = ""
                                        var amt = 0.0
                                        ev.safeGetAsJsonArray("attributes")?.forEach { attr ->
                                            val k = attr.asJsonObject.get("key")?.asString
                                            val v = attr.asJsonObject.get("value")?.asString ?: ""
                                            if (k == "delegator") delegatorInEvent = v.trim().lowercase()
                                            if (k == "amount") {
                                                val m = Regex("(\\d+)basecro").find(v)
                                                if (m != null) amt += m.groupValues[1].toDoubleOrNull() ?: 0.0
                                            }
                                        }
                                        if (delegatorInEvent == safeAddress || delegatorInEvent.isEmpty()) {
                                            if (isExplicitClaim) txManualClaimRaw += amt
                                            else if (isShiftAction) txShiftRaw += amt
                                            else txManualClaimRaw += amt
                                        }
                                    } else if (et == "withdraw_commission") {
                                        ev.safeGetAsJsonArray("attributes")?.forEach { attr ->
                                            if (attr.asJsonObject.get("key")?.asString == "amount") {
                                                val m = Regex("(\\d+)basecro").find(
                                                    attr.asJsonObject.get("value")?.asString ?: "")
                                                if (m != null) txCommissionRaw += m.groupValues[1].toDoubleOrNull() ?: 0.0
                                            }
                                        }
                                    }
                                }

                                val distributionModuleAddr = "cro1jv65s3grqf6v6jl3dp4t6c9t9rk99cd8lyv94w"
                                if (isShiftAction && !isExplicitClaim && txShiftRaw == 0.0) {
                                    allEvents.forEach { event ->
                                        if (event.get("type")?.asString == "transfer") {
                                            var currentRecipient = ""
                                            var currentSender = ""
                                            var currentAmt = 0.0

                                            fun commitIfMatch() {
                                                if (currentRecipient.lowercase() == safeAddress &&
                                                    currentSender.lowercase() == distributionModuleAddr &&
                                                    currentAmt > 0.0) {
                                                    txShiftRaw += currentAmt
                                                }
                                            }

                                            event.safeGetAsJsonArray("attributes")?.forEach { attr ->
                                                val k = attr.asJsonObject.get("key")?.asString
                                                val v = attr.asJsonObject.get("value")?.asString ?: ""
                                                when (k) {
                                                    "recipient" -> {
                                                        commitIfMatch()
                                                        currentRecipient = v.trim()
                                                        currentSender = ""
                                                        currentAmt = 0.0
                                                    }
                                                    "sender" -> currentSender = v.trim()
                                                    "amount" -> {
                                                        val m = Regex("(\\d+)basecro").find(v)
                                                        if (m != null) currentAmt = m.groupValues[1].toDoubleOrNull() ?: 0.0
                                                    }
                                                }
                                            }
                                            commitIfMatch()
                                        }
                                    }
                                }

                                if (txManualClaimRaw == 0.0 && txShiftRaw == 0.0 && txCommissionRaw == 0.0) {
                                    try {
                                        val idxUrl = "$indexerUrl/transactions/$hash"
                                        val idxReq = okhttp3.Request.Builder()
                                            .url(idxUrl).header("User-Agent", "Mozilla/5.0").build()
                                        val idxRes = client.newCall(idxReq).execute()
                                        if (idxRes.isSuccessful) {
                                            val idxRoot = com.google.gson.JsonParser
                                                .parseString(idxRes.body?.string() ?: "{}").asJsonObject
                                            idxRes.close()
                                            idxRoot.getAsJsonObject("result")
                                                ?.getAsJsonArray("messages")
                                                ?.forEach { msgEl ->
                                                    val msgObj = msgEl.asJsonObject
                                                    val typeStr = (msgObj.get("type")?.asString ?: "").lowercase()
                                                    val content = msgObj.getAsJsonObject("content") ?: return@forEach
                                                    val delAddr = (content.get("delegatorAddress")?.asString ?: "")
                                                        .trim().lowercase()
                                                    if (delAddr.isNotEmpty() && delAddr != safeAddress) return@forEach
                                                    val amt = content.getAsJsonArray("amount")
                                                        ?.filter { it.asJsonObject.get("denom")?.asString == "basecro" }
                                                        ?.sumOf { it.asJsonObject.get("amount")?.asString?.toDoubleOrNull() ?: 0.0 }
                                                        ?: 0.0
                                                    val autoAmt = content.getAsJsonObject("autoClaimedRewards")
                                                        ?.let { if (it.get("denom")?.asString == "basecro")
                                                            it.get("amount")?.asString?.toDoubleOrNull() ?: 0.0
                                                        else 0.0 } ?: 0.0
                                                    when {
                                                        typeStr.contains("withdrawdelegatorreward") ->
                                                            txManualClaimRaw += amt
                                                        typeStr.endsWith("msgdelegate") ||
                                                                typeStr.endsWith("msgundelegate") ||
                                                                typeStr.endsWith("msgbeginredelegate") ->
                                                            if (autoAmt > 0.0) txShiftRaw += autoAmt
                                                        typeStr.contains("withdrawvalidatorcommission") ->
                                                            txCommissionRaw += amt
                                                    }
                                                }
                                        } else {
                                            idxRes.close()
                                        }
                                    } catch (e: Exception) {}
                                }
                            }

                            if (authzCompoundRaw > 0.0 || txManualClaimRaw > 0.0 ||
                                txCommissionRaw > 0.0 || txShiftRaw > 0.0) {
                                runningTotalBaseCro += authzCompoundRaw + txManualClaimRaw + txCommissionRaw + txShiftRaw
                                restakeCompounds += (authzCompoundRaw / 100000000.0)
                                manualClaims += (txManualClaimRaw / 100000000.0)
                                validatorCommissions += (txCommissionRaw / 100000000.0)
                                autoClaims += (txShiftRaw / 100000000.0)
                                processedHashes.add(hash)
                            }
                        } catch (e: Exception) {
                            android.util.Log.e("SYNC_SRC", "S1 per-tx FAILED i=$i page=$s1Page err=${e.message}")
                        }
                    }
                    withContext(Dispatchers.Main) { onProgress(s1Page, s1Total) }
                    if (txs.size() < limit) s1Finished = true else s1Page++
                } catch (e: Exception) {
                    android.util.Log.e("SYNC_ERROR", "S1 exception at page=$s1Page: ${e.message}", e)
                    if (retryCount < 3) {
                        retryCount++
                        kotlinx.coroutines.delay(2000L * retryCount)
                        continue
                    }
                    retryCount = 0
                    if (s1Page <= 3) {
                        android.util.Log.w("SYNC_SRC", "S1 bailing early — REST API timing out on first pages")
                        s1Finished = true
                    } else {
                        s1Page++
                    }
                }
            }

            if (savedBlockHeight == 0 && s1Page < 10 && processedHashes.size < 1000) {
                android.util.Log.w("SYNC_SRC", "S1 WARNING: fresh sync but only $s1Page pages / ${processedHashes.size} hashes — REST API may have returned incomplete data")
                // Notify user via sync state
                withContext(Dispatchers.Main) {
                    SyncStateHolder.postMessage("REST API was slow — using backup source. Results should still be accurate.")
                }
            }

            withContext(Dispatchers.Main) { onProgress(0, 0) }
            android.util.Log.d("SYNC_SRC", "S1 done. pages=$s1Page hashes=${processedHashes.size} manual=$manualClaims auto=$autoClaims commissions=$validatorCommissions restake=$restakeCompounds")

            // =========================================================================
            // SOURCE 2: INDEXER API - walked backwards (highest page first)
            // =========================================================================
            var s2CurrentPage = 0
            var s2TotalPages = 0
            var s2Initialized = false
            var s2Finished = false
            var s2PagesDone = 0
            retryCount = 0

            while (isActive && !s2Finished && !isCancelled()) {
                try {
                    val pageToFetch = if (!s2Initialized) 1 else s2CurrentPage
                    val urlStr = "$indexerUrl/accounts/$address/transactions?limit=$limit&page=$pageToFetch"
                    val req = okhttp3.Request.Builder().url(urlStr).header("User-Agent", "Mozilla/5.0").build()
                    val res = client.newCall(req).execute()

                    if (!res.isSuccessful) {
                        res.close()
                        if (retryCount < 2) { retryCount++; kotlinx.coroutines.delay(1000); continue }
                        else { s2Finished = true; break }
                    }
                    retryCount = 0

                    val jsonStr = res.body?.string() ?: ""
                    res.close()
                    val root = com.google.gson.JsonParser.parseString(jsonStr).asJsonObject
                    val resultArr = root.safeGetAsJsonArray("result")

                    if (resultArr == null || resultArr.size() == 0) {
                        if (s2Initialized && s2CurrentPage > 1) {
                            android.util.Log.w("SYNC_SRC", "S2 page $s2CurrentPage returned empty, skipping")
                            s2CurrentPage--
                            continue
                        }
                        s2Finished = true
                        continue
                    }

                    if (!s2Initialized) {
                        val pagination = root.getAsJsonObject("pagination")
                        s2TotalPages = pagination?.get("total_page")?.asInt ?: 1
                        s2CurrentPage = s2TotalPages
                        s2Initialized = true
                        android.util.Log.d("SYNC_SRC", "S2 initialized: totalPages=$s2TotalPages, starting from page $s2CurrentPage")
                        if (s2TotalPages > 1) continue
                    }

                    if (savedBlockHeight > 0) {
                        val lastTxOnPage = resultArr.get(resultArr.size() - 1).asJsonObject
                        val lastHgt = lastTxOnPage.get("blockHeight")?.asInt ?: 0
                        if (lastHgt <= savedBlockHeight) {
                            s2Finished = true; continue
                        }
                    }

                    for (i in 0 until resultArr.size()) {
                        try {
                            val tx = resultArr.get(i).asJsonObject
                            val hash = tx.get("hash")?.asString ?: ""
                            val hgt = tx.get("blockHeight")?.asInt ?: 0
                            val success = tx.get("success")?.asBoolean ?: false

                            if (hgt > newestBlockHeightThisSession) {
                                newestBlockHeightThisSession = hgt
                                newestHashThisSession = hash
                            }
                            if (!success || processedHashes.contains(hash)) continue
                            if (savedBlockHeight > 0 && hgt <= savedBlockHeight) continue

                            val logElement = tx.get("log")
                            val logStr = when {
                                logElement == null || logElement.isJsonNull -> ""
                                logElement.isJsonArray -> logElement.toString()
                                logElement.isJsonPrimitive -> logElement.asString ?: ""
                                logElement.isJsonObject -> logElement.toString()
                                else -> ""
                            }

                            if (logStr.isEmpty() || logStr == "[]") {
                                val msgTypes = tx.safeGetAsJsonArray("messageTypes")
                                    ?.map { it.asString.lowercase() } ?: emptyList()
                                val isRelevant = msgTypes.any { t ->
                                    t.contains("withdrawdelegatorreward") ||
                                            t.contains("withdrawvalidatorcommission") ||
                                            t.contains("msgdelegate") ||
                                            t.contains("msgundelegate") ||
                                            t.contains("msgbeginredelegate")
                                }
                                if (!isRelevant) continue

                                val messagesArr = tx.safeGetAsJsonArray("messages")
                                if (messagesArr == null || messagesArr.size() == 0) continue

                                var txManualClaimRaw = 0.0
                                var txShiftRaw = 0.0
                                var txCommissionRaw = 0.0

                                tx.safeGetAsJsonArray("messages")?.forEach { msgEl ->
                                    val msgObj = msgEl.asJsonObject
                                    val typeStr = (msgObj.get("type")?.asString ?: "").lowercase()
                                    val content = msgObj.getAsJsonObject("content") ?: return@forEach
                                    val delAddr = (content.get("delegatorAddress")?.asString ?: "").trim().lowercase()
                                    if (delAddr.isNotEmpty() && delAddr != safeAddress) return@forEach

                                    val amt = content.safeGetAsJsonArray("amount")
                                        ?.filter { it.asJsonObject.get("denom")?.asString == "basecro" }
                                        ?.sumOf { it.asJsonObject.get("amount")?.asString?.toDoubleOrNull() ?: 0.0 }
                                        ?: 0.0
                                    val autoAmt = content.getAsJsonObject("autoClaimedRewards")
                                        ?.let { if (it.get("denom")?.asString == "basecro")
                                            it.get("amount")?.asString?.toDoubleOrNull() ?: 0.0
                                        else 0.0 } ?: 0.0

                                    when {
                                        typeStr.contains("withdrawdelegatorreward") -> txManualClaimRaw += amt
                                        typeStr.endsWith("msgdelegate") ||
                                                typeStr.endsWith("msgundelegate") ||
                                                typeStr.endsWith("msgbeginredelegate") ->
                                            if (autoAmt > 0.0) txShiftRaw += autoAmt
                                        typeStr.contains("withdrawvalidatorcommission") -> txCommissionRaw += amt
                                    }
                                }

                                if (txManualClaimRaw > 0.0 || txShiftRaw > 0.0 || txCommissionRaw > 0.0) {
                                    runningTotalBaseCro += txManualClaimRaw + txShiftRaw + txCommissionRaw
                                    manualClaims += (txManualClaimRaw / 100000000.0)
                                    autoClaims += (txShiftRaw / 100000000.0)
                                    validatorCommissions += (txCommissionRaw / 100000000.0)
                                    processedHashes.add(hash)
                                }
                                continue
                            }

                            val allEvents = mutableListOf<com.google.gson.JsonObject>()
                            try {
                                com.google.gson.JsonParser.parseString(logStr).asJsonArray.forEach { logItem ->
                                    logItem.asJsonObject.safeGetAsJsonArray("events")
                                        ?.forEach { allEvents.add(it.asJsonObject) }
                                }
                            } catch (e: Exception) {}

                            var isBotCompound = false
                            var isManualShift = false
                            var isExplicitClaim = false

                            allEvents.forEach { ev ->
                                if (ev.get("type")?.asString == "message") {
                                    ev.safeGetAsJsonArray("attributes")?.forEach { attr ->
                                        if (attr.asJsonObject.get("key")?.asString == "action") {
                                            val v = attr.asJsonObject.get("value")?.asString ?: ""
                                            if (v.contains("MsgExec") || v.contains("authz")) isBotCompound = true
                                            if (v.contains("Delegate") || v.contains("Undelegate") ||
                                                v.contains("BeginRedelegate")) isManualShift = true
                                            if (v.contains("WithdrawDelegatorReward")) isExplicitClaim = true
                                        }
                                    }
                                }
                            }

                            var txManualClaimRaw = 0.0
                            var txShiftRaw = 0.0
                            var authzCompoundRaw = 0.0
                            var txCommissionRaw = 0.0

                            allEvents.forEach { event ->
                                val eventType = event.get("type")?.asString ?: ""

                                if (eventType == "delegate") {
                                    var isOurDelegation = false
                                    var amt = 0.0
                                    event.safeGetAsJsonArray("attributes")?.forEach { attr ->
                                        val k = attr.asJsonObject.get("key")?.asString
                                        val v = attr.asJsonObject.get("value")?.asString ?: ""
                                        if (k == "delegator" && v.trim().lowercase() == safeAddress) isOurDelegation = true
                                        if (k == "amount") {
                                            val m = Regex("(\\d+)basecro").find(v)
                                            if (m != null) amt += m.groupValues[1].toDoubleOrNull() ?: 0.0
                                        }
                                    }
                                    if (isOurDelegation && isBotCompound) authzCompoundRaw += amt
                                }

                                if (eventType == "withdraw_rewards") {
                                    var eventDelegator = ""
                                    var amt = 0.0
                                    event.safeGetAsJsonArray("attributes")?.forEach { attr ->
                                        val k = attr.asJsonObject.get("key")?.asString
                                        val v = attr.asJsonObject.get("value")?.asString ?: ""
                                        if (k == "delegator") eventDelegator = v.trim().lowercase()
                                        if (k == "amount") {
                                            val m = Regex("(\\d+)basecro").find(v)
                                            if (m != null) amt += m.groupValues[1].toDoubleOrNull() ?: 0.0
                                        }
                                    }
                                    if (eventDelegator == safeAddress || eventDelegator.isEmpty()) {
                                        if (!isBotCompound) {
                                            if (isManualShift && !isExplicitClaim) txShiftRaw += amt
                                            else txManualClaimRaw += amt
                                        }
                                    }
                                }

                                if (eventType == "withdraw_commission") {
                                    event.safeGetAsJsonArray("attributes")?.forEach { attr ->
                                        val k = attr.asJsonObject.get("key")?.asString
                                        val v = attr.asJsonObject.get("value")?.asString ?: ""
                                        if (k == "amount") {
                                            val m = Regex("(\\d+)basecro").find(v)
                                            if (m != null) txCommissionRaw += m.groupValues[1].toDoubleOrNull() ?: 0.0
                                        }
                                    }
                                }
                            }

                            val distributionModuleAddr = "cro1jv65s3grqf6v6jl3dp4t6c9t9rk99cd8lyv94w"
                            if (isManualShift && !isExplicitClaim && txShiftRaw == 0.0 && !isBotCompound) {
                                allEvents.forEach { event ->
                                    if (event.get("type")?.asString == "transfer") {
                                        var currentRecipient = ""
                                        var currentSender = ""
                                        var currentAmt = 0.0

                                        fun commitIfMatch() {
                                            if (currentRecipient.lowercase() == safeAddress &&
                                                currentSender.lowercase() == distributionModuleAddr &&
                                                currentAmt > 0.0) {
                                                txShiftRaw += currentAmt
                                            }
                                        }

                                        event.safeGetAsJsonArray("attributes")?.forEach { attr ->
                                            val k = attr.asJsonObject.get("key")?.asString
                                            val v = attr.asJsonObject.get("value")?.asString ?: ""
                                            when (k) {
                                                "recipient" -> {
                                                    commitIfMatch()
                                                    currentRecipient = v.trim()
                                                    currentSender = ""
                                                    currentAmt = 0.0
                                                }
                                                "sender" -> currentSender = v.trim()
                                                "amount" -> {
                                                    val m = Regex("(\\d+)basecro").find(v)
                                                    if (m != null) currentAmt = m.groupValues[1].toDoubleOrNull() ?: 0.0
                                                }
                                            }
                                        }
                                        commitIfMatch()
                                    }
                                }
                            }

                            if (authzCompoundRaw > 0.0 || txManualClaimRaw > 0.0 ||
                                txCommissionRaw > 0.0 || txShiftRaw > 0.0) {
                                runningTotalBaseCro += authzCompoundRaw + txManualClaimRaw + txCommissionRaw + txShiftRaw
                                restakeCompounds += (authzCompoundRaw / 100000000.0)
                                manualClaims += (txManualClaimRaw / 100000000.0)
                                validatorCommissions += (txCommissionRaw / 100000000.0)
                                autoClaims += (txShiftRaw / 100000000.0)
                                processedHashes.add(hash)
                            }
                        } catch (e: Exception) {
                            android.util.Log.e("SYNC_SRC", "S2 per-tx FAILED i=$i page=$s2CurrentPage err=${e.message}")
                        }
                    }

                    s2PagesDone++
                    withContext(Dispatchers.Main) { onProgress(s2PagesDone, s2TotalPages) }

                    if (s2CurrentPage <= 1) s2Finished = true
                    else s2CurrentPage--

                } catch (e: Exception) {
                    android.util.Log.e("SYNC_ERROR", "S2 exception at page=$s2CurrentPage: ${e.message}", e)
                    if (retryCount < 3) {
                        retryCount++
                        kotlinx.coroutines.delay(2000L * retryCount)
                        continue
                    }
                    retryCount = 0
                    if (s2CurrentPage <= 1) s2Finished = true else s2CurrentPage--
                }
            }

            android.util.Log.d("SYNC_SRC", "S2 done. pages=$s2PagesDone/$s2TotalPages hashes=${processedHashes.size} manual=$manualClaims auto=$autoClaims commissions=$validatorCommissions restake=$restakeCompounds")

            withContext(Dispatchers.Main) { onProgress(0, 0) }

            // =========================================================================
            // SOURCE 3: BOT INDEXER - catches authz restakes missed by S1+S2
            // =========================================================================
            var botAddress: String? = null
            for (botRetry in 1..3) {
                try {
                    val query = "withdraw_rewards.delegator%3D%27$address%27"
                    val url = "$nodeUrl/cosmos/tx/v1beta1/txs" +
                            "?query=$query&limit=1&page=1&order_by=ORDER_BY_DESC"
                    val req = okhttp3.Request.Builder().url(url).header("User-Agent", "Mozilla/5.0").build()
                    val res = client.newCall(req).execute()
                    if (res.isSuccessful) {
                        val root = com.google.gson.JsonParser.parseString(res.body?.string() ?: "{}").asJsonObject
                        res.close()
                        root.getAsJsonArray("txs")?.get(0)?.asJsonObject
                            ?.getAsJsonObject("body")
                            ?.getAsJsonArray("messages")
                            ?.forEach { msg ->
                                val typeStr = (msg.asJsonObject.get("@type")?.asString ?: "").lowercase()
                                if (typeStr.contains("msgexec") || typeStr.contains("authz")) {
                                    botAddress = msg.asJsonObject.get("grantee")?.asString
                                }
                            }
                        if (botAddress != null) break
                    } else {
                        res.close()
                    }
                } catch (e: Exception) {}
                if (botRetry < 3) kotlinx.coroutines.delay(2000L * botRetry)
            }

            if (!botAddress.isNullOrEmpty()) {
                var s3CurrentPage = 0
                var s3TotalPages = 0
                var s3Initialized = false
                var s3Finished = false
                var s3PagesDone = 0
                retryCount = 0

                while (isActive && !s3Finished && !isCancelled()) {
                    try {
                        val pageToFetch = if (!s3Initialized) 1 else s3CurrentPage
                        val urlStr = "$indexerUrl/accounts/$botAddress/transactions?limit=$limit&page=$pageToFetch"
                        val req = okhttp3.Request.Builder()
                            .url(urlStr).header("User-Agent", "Mozilla/5.0").build()
                        val res = client.newCall(req).execute()

                        if (!res.isSuccessful) {
                            res.close()
                            if (retryCount < 2) { retryCount++; kotlinx.coroutines.delay(1000); continue }
                            else { s3Finished = true; break }
                        }
                        retryCount = 0

                        val jsonStr = res.body?.string() ?: ""
                        res.close()
                        val root = com.google.gson.JsonParser.parseString(jsonStr).asJsonObject
                        val resultArr = root.getAsJsonArray("result")

                        if (resultArr == null || resultArr.size() == 0) { s3Finished = true; continue }

                        if (!s3Initialized) {
                            val pagination = root.getAsJsonObject("pagination")
                            s3TotalPages = pagination?.get("total_page")?.asInt ?: 1
                            s3CurrentPage = s3TotalPages
                            s3Initialized = true
                            if (s3TotalPages > 1) continue
                        }

                        if (savedBlockHeight > 0) {
                            val lastTxOnPage = resultArr.get(resultArr.size() - 1).asJsonObject
                            val lastHgt = lastTxOnPage.get("blockHeight")?.asInt ?: 0
                            if (lastHgt <= savedBlockHeight) { s3Finished = true; continue }
                        }

                        for (i in 0 until resultArr.size()) {
                            val tx = resultArr.get(i).asJsonObject
                            val hash = tx.get("hash")?.asString ?: ""
                            val hgt = tx.get("blockHeight")?.asInt ?: 0
                            val success = tx.get("success")?.asBoolean ?: false

                            if (hgt > newestBlockHeightThisSession) {
                                newestBlockHeightThisSession = hgt
                                newestHashThisSession = hash
                            }
                            if (!success || processedHashes.contains(hash)) continue
                            if (savedBlockHeight > 0 && hgt <= savedBlockHeight) continue

                            val msgTypes = tx.safeGetAsJsonArray("messageTypes")
                                ?.map { it.asString } ?: emptyList()
                            val isAuthzExec = msgTypes.any { it.contains("MsgExec") || it.contains("authz.v1beta1.MsgExec") }
                            if (msgTypes.isNotEmpty() && !isAuthzExec) continue

                            var authzCompoundRaw = 0.0
                            val logElement = tx.get("log")
                            val logStr = when {
                                logElement == null || logElement.isJsonNull -> ""
                                logElement.isJsonArray -> logElement.toString()
                                logElement.isJsonPrimitive -> logElement.asString ?: ""
                                logElement.isJsonObject -> logElement.toString()
                                else -> ""
                            }

                            if (logStr.isNotEmpty() && logStr != "[]") {
                                try {
                                    val allEvents = mutableListOf<com.google.gson.JsonObject>()
                                    com.google.gson.JsonParser.parseString(logStr).asJsonArray
                                        .forEach { logItem ->
                                            logItem.asJsonObject.safeGetAsJsonArray("events")
                                                ?.forEach { allEvents.add(it.asJsonObject) }
                                        }

                                    // Modern schema: delegate events have delegator attribute
                                    allEvents.forEach { ev ->
                                        if (ev.get("type")?.asString == "delegate") {
                                            var isOurs = false
                                            var amt = 0.0
                                            ev.safeGetAsJsonArray("attributes")?.forEach { attr ->
                                                val k = attr.asJsonObject.get("key")?.asString
                                                val v = attr.asJsonObject.get("value")?.asString ?: ""
                                                if (k == "delegator" && v.trim().lowercase() == safeAddress) isOurs = true
                                                if (k == "amount") {
                                                    val m = Regex("(\\d+)basecro").find(v)
                                                    if (m != null) amt += m.groupValues[1].toDoubleOrNull() ?: 0.0
                                                }
                                            }
                                            if (isOurs) authzCompoundRaw += amt
                                        }
                                    }

                                    // Old schema fallback: transfer from distribution module
                                    if (authzCompoundRaw == 0.0) {
                                        val distributionModuleAddr = "cro1jv65s3grqf6v6jl3dp4t6c9t9rk99cd8lyv94w"
                                        allEvents.forEach { event ->
                                            if (event.get("type")?.asString == "transfer") {
                                                var currentRecipient = ""
                                                var currentSender = ""
                                                var currentAmt = 0.0

                                                fun commitIfMatch() {
                                                    if (currentRecipient.lowercase() == safeAddress &&
                                                        currentSender.lowercase() == distributionModuleAddr &&
                                                        currentAmt > 0.0) {
                                                        authzCompoundRaw += currentAmt
                                                    }
                                                }

                                                event.safeGetAsJsonArray("attributes")?.forEach { attr ->
                                                    val k = attr.asJsonObject.get("key")?.asString
                                                    val v = attr.asJsonObject.get("value")?.asString ?: ""
                                                    when (k) {
                                                        "recipient" -> {
                                                            commitIfMatch()
                                                            currentRecipient = v.trim()
                                                            currentSender = ""
                                                            currentAmt = 0.0
                                                        }
                                                        "sender" -> currentSender = v.trim()
                                                        "amount" -> {
                                                            val m = Regex("(\\d+)basecro").find(v)
                                                            if (m != null) currentAmt = m.groupValues[1].toDoubleOrNull() ?: 0.0
                                                        }
                                                    }
                                                }
                                                commitIfMatch()
                                            }
                                        }
                                    }
                                } catch (e: Exception) {}
                            } else {
                                // REST fallback for empty-log txs
                                try {
                                    val restUrl = "$nodeUrl/cosmos/tx/v1beta1/txs/$hash"
                                    val restReq = okhttp3.Request.Builder()
                                        .url(restUrl).header("User-Agent", "Mozilla/5.0").build()
                                    val restRes = client.newCall(restReq).execute()
                                    if (restRes.isSuccessful) {
                                        val restRoot = com.google.gson.JsonParser
                                            .parseString(restRes.body?.string() ?: "{}").asJsonObject
                                        restRes.close()
                                        restRoot.getAsJsonObject("tx")
                                            ?.getAsJsonObject("body")
                                            ?.getAsJsonArray("messages")
                                            ?.forEach { msg ->
                                                val typeStr = (msg.asJsonObject.get("@type")?.asString ?: "").lowercase()
                                                if (typeStr.contains("msgexec") || typeStr.contains("authz")) {
                                                    msg.asJsonObject.getAsJsonArray("msgs")
                                                        ?.forEach { inner ->
                                                            val innerType = inner.asJsonObject.get("@type")?.asString ?: ""
                                                            if (innerType == "/cosmos.staking.v1beta1.MsgDelegate" &&
                                                                inner.asJsonObject.get("delegator_address")?.asString
                                                                    ?.trim()?.lowercase() == safeAddress) {
                                                                authzCompoundRaw += inner.asJsonObject
                                                                    .getAsJsonObject("amount")
                                                                    ?.get("amount")?.asString
                                                                    ?.toDoubleOrNull() ?: 0.0
                                                            }
                                                        }
                                                }
                                            }
                                    } else {
                                        restRes.close()
                                    }
                                } catch (e: Exception) {}
                            }

                            if (authzCompoundRaw > 0.0) {
                                runningTotalBaseCro += authzCompoundRaw
                                restakeCompounds += (authzCompoundRaw / 100000000.0)
                                processedHashes.add(hash)
                            }
                        }

                        s3PagesDone++
                        withContext(Dispatchers.Main) { onProgress(s3PagesDone, s3TotalPages) }

                        if (s3CurrentPage <= 1) s3Finished = true
                        else s3CurrentPage--

                    } catch (e: Exception) {
                        android.util.Log.e("SYNC_ERROR", "S3 exception at page=$s3CurrentPage: ${e.message}", e)
                        if (retryCount < 3) {
                            retryCount++
                            kotlinx.coroutines.delay(2000L * retryCount)
                            continue
                        }
                        retryCount = 0
                        if (s3CurrentPage <= 1) s3Finished = true else s3CurrentPage--
                    }
                }
            }

            val finalBreakdown = mapOf(
                "Manual Claims" to manualClaims,
                "Auto-Restake" to restakeCompounds,
                "Staking Auto-Claims" to autoClaims,
                "Validator Commissions" to validatorCommissions
            )

            withContext(Dispatchers.Main) { onBreakdown(finalBreakdown) }

            val finalResult = (runningTotalBaseCro / 100000000.0) +
                    (if (savedBlockHeight > 0) savedTotal else 0.0)
            // Don't save if fresh sync produced suspiciously low results
            if (savedBlockHeight == 0 && finalResult < 1.0 && processedHashes.size < 10) {
                android.util.Log.w("SYNC_SRC", "Refusing to save — fresh sync produced near-zero results, likely API failure")
                withContext(Dispatchers.Main) {
                    SyncStateHolder.postMessage("Sync failed — APIs returned incomplete data. Please try again later.")
                }
                return@withContext savedTotal
            }
            android.util.Log.d("SYNC_SRC", "Saving state. total=$finalResult manual=$manualClaims commissions=$validatorCommissions auto=$autoClaims restake=$restakeCompounds hash=$newestHashThisSession")
            onSaveState(finalResult, newestHashThisSession ?: savedHash ?: "", finalBreakdown)

            return@withContext finalResult

        } catch (e: Exception) {
            android.util.Log.e("SYNC_ERROR", "syncAllTimeRewards crashed: ${e.message}", e)
            e.printStackTrace()
            return@withContext savedTotal
        }
    }

    suspend fun getAccountAgeInYears(address: String): Double = withContext(Dispatchers.IO) {
        try {
            val url = "$nodeUrl/cosmos/tx/v1beta1/txs?query=withdraw_rewards.delegator%3D%27$address%27&order_by=ORDER_BY_ASC&limit=1"
            val root = fetchJson(url) ?: return@withContext 1.0

            val txs = root.getAsJsonArray("tx_responses")
            if (txs == null || txs.size() == 0) return@withContext 1.0

            val oldestTx = txs.get(0).asJsonObject
            val timestampStr = oldestTx.get("timestamp").asString

            val cleanTs = timestampStr.take(19) + "Z"
            val format = java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", java.util.Locale.US)
            format.timeZone = java.util.TimeZone.getTimeZone("UTC")

            val oldestTime = format.parse(cleanTs)?.time ?: System.currentTimeMillis()
            val diffMillis = System.currentTimeMillis() - oldestTime
            val years = diffMillis / (1000.0 * 60 * 60 * 24 * 365.25)

            if (years > 0.08) {
                return@withContext years
            }

            val totalTxs = root.get("total")?.asString?.toIntOrNull() ?: return@withContext 1.0
            if (totalTxs <= 1) return@withContext 1.0

            val limit = 100
            val lastPage = (totalTxs + limit - 1) / limit

            val fallbackUrl = "$nodeUrl/cosmos/tx/v1beta1/txs?query=withdraw_rewards.delegator%3D%27$address%27&limit=$limit&page=$lastPage"
            val fallbackRoot = fetchJson(fallbackUrl) ?: return@withContext 1.0
            val fallbackTxs = fallbackRoot.getAsJsonArray("tx_responses")
            if (fallbackTxs == null || fallbackTxs.size() == 0) return@withContext 1.0

            val fallbackOldestTx = fallbackTxs.get(fallbackTxs.size() - 1).asJsonObject
            val fallbackTimestampStr = fallbackOldestTx.get("timestamp").asString
            val fallbackCleanTs = fallbackTimestampStr.take(19) + "Z"

            val fallbackOldestTime = format.parse(fallbackCleanTs)?.time ?: System.currentTimeMillis()
            val fallbackYears = (System.currentTimeMillis() - fallbackOldestTime) / (1000.0 * 60 * 60 * 24 * 365.25)

            return@withContext if (fallbackYears > 0.08) fallbackYears else 1.0

        } catch (e: Exception) {
            return@withContext 1.0
        }
    }

    data class ValidatorStats(
        val totalBondedCro: Double,
        val commissionRate: Double,
        val status: String,
        val jailed: Boolean,
        val minSelfDelegation: Double,
        val delegatorCount: Int,
        val selfBondedCro: Double // NEW: Holds the operator's exact delegation
    )

    suspend fun getValidatorStats(validatorAddress: String, operatorWalletAddress: String): ValidatorStats? {
        return kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            try {
                // 1. Fetch Main Validator Info
                val url = java.net.URL("https://rest.mainnet.crypto.org/cosmos/staking/v1beta1/validators/$validatorAddress")
                val response = url.readText()

                // STRICT STATIC CALL: com.google.gson.JsonParser.parseString()
                val valObj = com.google.gson.JsonParser.parseString(response).getAsJsonObject().get("validator").getAsJsonObject()

                val tokensBaseCro = valObj.get("tokens").getAsString().toDouble()
                val totalBonded = tokensBaseCro / 100_000_000.0

                val commissionStr = valObj.get("commission").getAsJsonObject().get("commission_rates").getAsJsonObject().get("rate").getAsString()
                val commissionRate = commissionStr.toDouble() * 100.0

                val statusRaw = valObj.get("status").getAsString()
                val status = if (statusRaw == "BOND_STATUS_BONDED") "ACTIVE" else "INACTIVE"

                val jailed = valObj.get("jailed").getAsBoolean()
                val minSelfDelStr = valObj.get("min_self_delegation").getAsString()
                val minSelfDelegation = minSelfDelStr.toDouble() / 100_000_000.0

                // 2. Fetch Delegator Count
                var delegatorCount = 0
                try {
                    val delUrl = java.net.URL("https://rest.mainnet.crypto.org/cosmos/staking/v1beta1/validators/$validatorAddress/delegations?pagination.limit=1&pagination.count_total=true")
                    val delRes = delUrl.readText()
                    val totalStr = com.google.gson.JsonParser.parseString(delRes).getAsJsonObject().get("pagination").getAsJsonObject().get("total").getAsString()
                    delegatorCount = totalStr.toInt()
                } catch (e: Exception) {}

                // 3. Fetch Self-Bonded Tokens (The Operator's Delegation)
                var selfBondedCro = 0.0
                try {
                    val selfDelUrl = java.net.URL("https://rest.mainnet.crypto.org/cosmos/staking/v1beta1/validators/$validatorAddress/delegations/$operatorWalletAddress")
                    val selfDelRes = selfDelUrl.readText()
                    val selfDelStr = com.google.gson.JsonParser.parseString(selfDelRes).getAsJsonObject().get("delegation_response").getAsJsonObject().get("balance").getAsJsonObject().get("amount").getAsString()
                    selfBondedCro = selfDelStr.toDouble() / 100_000_000.0
                } catch (e: Exception) {}

                return@withContext ValidatorStats(totalBonded, commissionRate, status, jailed, minSelfDelegation, delegatorCount, selfBondedCro)
            } catch (e: Exception) {
                return@withContext null
            }
        }
    }

    suspend fun getAccountState(address: String): Pair<String, String>? = withContext(Dispatchers.IO) {
        try {
            val json = fetchJson("$nodeUrl/cosmos/auth/v1beta1/accounts/$address") ?: return@withContext null

            val account = if (json.getAsJsonObject("account").has("base_vesting_account")) {
                json.getAsJsonObject("account").getAsJsonObject("base_vesting_account").getAsJsonObject("base_account")
            } else {
                json.getAsJsonObject("account")
            }

            val accNum = account?.get("account_number")?.asString ?: "0"
            val seq = account?.get("sequence")?.asString ?: "0"
            return@withContext Pair(accNum, seq)
        } catch (e: Exception) {
            return@withContext null
        }
    }

    suspend fun broadcastTransaction(txBytesBase64: String): Pair<Boolean, String> = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
        val url = "$nodeUrl/cosmos/tx/v1beta1/txs"

        val jsonPayload = """
            {
                "tx_bytes": "$txBytesBase64",
                "mode": "BROADCAST_MODE_SYNC"
            }
        """.trimIndent()

        val mediaType = "application/json".toMediaTypeOrNull()
        val requestBody = jsonPayload.toRequestBody(mediaType)

        val request = okhttp3.Request.Builder()
            .url(url)
            .post(requestBody)
            .build()

        try {
            val response = client.newCall(request).execute()

            val responseBody = response.body?.string() ?: ""

            val jsonResponse = com.google.gson.JsonParser.parseString(responseBody).asJsonObject
            if (jsonResponse.has("tx_response")) {
                val txResponse = jsonResponse.getAsJsonObject("tx_response")
                val code = txResponse.get("code").asInt
                val txHash = txResponse.get("txhash").asString

                if (code == 0) {
                    return@withContext Pair(true, "Success: $txHash")
                } else {
                    val rawLog = txResponse.get("raw_log").asString
                    return@withContext Pair(false, "Failed (Code $code): $rawLog")
                }
            }
            return@withContext Pair(false, "Invalid node response")

        } catch (e: Exception) {
            return@withContext Pair(false, "Network Error: ${e.message}")
        }
    }

    suspend fun verifyTransaction(txHash: String, maxAttempts: Int = 5): Pair<Boolean, String> = withContext(Dispatchers.IO) {
        for (attempt in 1..maxAttempts) {
            kotlinx.coroutines.delay(6000L)
            try {
                val json = fetchJson("$nodeUrl/cosmos/tx/v1beta1/txs/$txHash") ?: continue
                val txResponse = json.getAsJsonObject("tx_response") ?: continue
                val code = txResponse.get("code")?.asInt ?: -1
                if (code == 0) {
                    return@withContext Pair(true, txHash)
                } else {
                    val rawLog = txResponse.get("raw_log")?.asString ?: "Unknown error"
                    return@withContext Pair(false, "Transaction failed (code $code): $rawLog")
                }
            } catch (e: Exception) {
                continue
            }
        }
        return@withContext Pair(false, "Could not verify transaction after $maxAttempts attempts")
    }

    suspend fun checkRestakeGrant(granter: String, grantee: String): String? = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
        try {
            val url = "$nodeUrl/cosmos/authz/v1beta1/grants?granter=$granter&grantee=$grantee&msg_type_url=/cosmos.staking.v1beta1.MsgDelegate"
            val root = fetchJson(url)
            val grants = root?.getAsJsonArray("grants")
            if (grants != null && grants.size() > 0) {
                return@withContext grants.get(0).asJsonObject.get("expiration")?.asString
            }
            return@withContext null
        } catch (e: Exception) {
            return@withContext null
        }
    }

    @OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)
    fun encodeAminoToProtobuf(payloadString: String): String {
        try {
            val sigMatch = Regex("""signature=([A-Za-z0-9+/=]+)\}""").find(payloadString)
            val rawSigString = sigMatch?.groupValues?.get(1) ?: throw Exception("Failed to extract signature.")
            val rawSignature = android.util.Base64.decode(rawSigString, android.util.Base64.NO_WRAP)

            val pubKeyMatch = Regex("""pub_key=\{type=[^,]+,\s*value=([^}]+)\}""").find(payloadString)
            val pubKeyBase64 = pubKeyMatch?.groupValues?.get(1) ?: throw Exception("Failed to extract public key.")
            val pubKeyBytes = android.util.Base64.decode(pubKeyBase64, android.util.Base64.NO_WRAP)

            val pubKeyProto = kotlinx.serialization.protobuf.ProtoBuf.encodeToByteArray(PubKeySecp256k1.serializer(), PubKeySecp256k1(pubKeyBytes))
            val pubKeyAny = ProtoAny("/cosmos.crypto.secp256k1.PubKey", pubKeyProto)

            val signedBlock = if (payloadString.contains("signed={")) {
                payloadString.substringAfter("signed={")
            } else payloadString

            val seqMatch = Regex("""sequence=(\d+)""").find(signedBlock)
            val sequence = seqMatch?.groupValues?.get(1)?.toLong() ?: throw Exception("Failed to extract sequence.")

            val memoMatch = Regex("""memo=([^,}]*)""").find(signedBlock)
            val memo = memoMatch?.groupValues?.get(1)?.trim() ?: ""

            val gasMatch = Regex("""gas=(\d+)""").find(signedBlock)
            val gasLimit = gasMatch?.groupValues?.get(1)?.toLong() ?: 300000L
            val feeMatch = Regex("""amount=(\d+)""").find(signedBlock)
            val feeAmount = feeMatch?.groupValues?.get(1) ?: "25000"
            val fee = Fee(listOf(Coin("basecro", feeAmount)), gasLimit)

            val parsedMsgs = mutableListOf<Pair<Int, ProtoAny>>()

            val delegateRegex = Regex("""type=cosmos-sdk/MsgDelegate,\s*value=\{amount=\{([^}]+)\},\s*delegator_address=([^,]+),\s*validator_address=([^,}]+)\}""")
            for (match in delegateRegex.findAll(signedBlock)) {
                val amountBlock = match.groupValues[1]
                val delAddr = match.groupValues[2].trim()
                val valAddr = match.groupValues[3].trim()
                val amount = Regex("""amount=(\d+)""").find(amountBlock)?.groupValues?.get(1) ?: "0"
                val msgDel = MsgDelegate(delAddr, valAddr, Coin("basecro", amount))
                val msgBytes = kotlinx.serialization.protobuf.ProtoBuf.encodeToByteArray(MsgDelegate.serializer(), msgDel)
                parsedMsgs.add(Pair(match.range.first, ProtoAny("/cosmos.staking.v1beta1.MsgDelegate", msgBytes)))
            }

            val withdrawRegex = Regex("""type=cosmos-sdk/MsgWithdrawDelegationReward,\s*value=\{delegator_address=([^,]+),\s*validator_address=([^,}]+)\}""")
            for (match in withdrawRegex.findAll(signedBlock)) {
                val msgWd = MsgWithdrawDelegationReward(match.groupValues[1].trim(), match.groupValues[2].trim())
                val msgBytes = kotlinx.serialization.protobuf.ProtoBuf.encodeToByteArray(MsgWithdrawDelegationReward.serializer(), msgWd)
                parsedMsgs.add(Pair(match.range.first, ProtoAny("/cosmos.distribution.v1beta1.MsgWithdrawDelegatorReward", msgBytes)))
            }

            val sendRegex = Regex("""type=cosmos-sdk/MsgSend,\s*value=\{amount=\[\{([^}]+)\}\],\s*from_address=([^,]+),\s*to_address=([^,}]+)\}""")
            for (match in sendRegex.findAll(signedBlock)) {
                val amountBlock = match.groupValues[1]
                val fromAddr = match.groupValues[2].trim()
                val toAddr = match.groupValues[3].trim()
                val amount = Regex("""amount=(\d+)""").find(amountBlock)?.groupValues?.get(1) ?: "0"
                val msgSend = MsgSend(fromAddr, toAddr, listOf(Coin("basecro", amount)))
                val msgBytes = kotlinx.serialization.protobuf.ProtoBuf.encodeToByteArray(MsgSend.serializer(), msgSend)
                parsedMsgs.add(Pair(match.range.first, ProtoAny("/cosmos.bank.v1beta1.MsgSend", msgBytes)))
            }

            val redelegateRegex = Regex("""type=cosmos-sdk/MsgBeginRedelegate,\s*value=\{amount=\{([^}]+)\},\s*delegator_address=([^,]+),\s*validator_dst_address=([^,]+),\s*validator_src_address=([^,}]+)\}""")
            for (match in redelegateRegex.findAll(signedBlock)) {
                val amountBlock = match.groupValues[1]
                val delAddr = match.groupValues[2].trim()
                val valDstAddr = match.groupValues[3].trim()
                val valSrcAddr = match.groupValues[4].trim()
                val amount = Regex("""amount=(\d+)""").find(amountBlock)?.groupValues?.get(1) ?: "0"
                val msgRedel = MsgBeginRedelegate(delAddr, valSrcAddr, valDstAddr, Coin("basecro", amount))
                val msgBytes = kotlinx.serialization.protobuf.ProtoBuf.encodeToByteArray(MsgBeginRedelegate.serializer(), msgRedel)
                parsedMsgs.add(Pair(match.range.first, ProtoAny("/cosmos.staking.v1beta1.MsgBeginRedelegate", msgBytes)))
            }

            val grantBlocks = signedBlock.split("type=cosmos-sdk/MsgGrant").drop(1)
            for (block in grantBlocks) {
                val msgType = Regex("""msg=([^}]+)""").find(block)?.groupValues?.get(1)?.trim() ?: "/cosmos.staking.v1beta1.MsgDelegate"
                val expirationStr = Regex("""expiration=([^,}]+)""").find(block)?.groupValues?.get(1)?.trim() ?: ""
                val grantee = Regex("""grantee=([^,}]+)""").find(block)?.groupValues?.get(1)?.trim() ?: ""
                val granter = Regex("""granter=([^,}]+)""").find(block)?.groupValues?.get(1)?.trim() ?: ""

                val format = java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", java.util.Locale.US)
                format.timeZone = java.util.TimeZone.getTimeZone("UTC")
                val expMillis = format.parse(expirationStr)?.time ?: 0L
                val seconds = expMillis / 1000

                val genericAuth = GenericAuthorization(msgType)
                val genericAuthBytes = kotlinx.serialization.protobuf.ProtoBuf.encodeToByteArray(GenericAuthorization.serializer(), genericAuth)
                val authAny = ProtoAny("/cosmos.authz.v1beta1.GenericAuthorization", genericAuthBytes)

                val grant = Grant(authAny, Timestamp(seconds, 0))
                val msgGrant = MsgGrant(granter, grantee, grant)
                val msgBytes = kotlinx.serialization.protobuf.ProtoBuf.encodeToByteArray(MsgGrant.serializer(), msgGrant)

                parsedMsgs.add(Pair(signedBlock.indexOf(block), ProtoAny("/cosmos.authz.v1beta1.MsgGrant", msgBytes)))
            }

            val revokeBlocks = signedBlock.split("type=cosmos-sdk/MsgRevoke").drop(1)
            for (block in revokeBlocks) {
                val grantee = Regex("""grantee=([^,}]+)""").find(block)?.groupValues?.get(1)?.trim() ?: ""
                val granter = Regex("""granter=([^,}]+)""").find(block)?.groupValues?.get(1)?.trim() ?: ""
                val msgTypeUrl = Regex("""msg_type_url=([^,}]+)""").find(block)?.groupValues?.get(1)?.trim() ?: "/cosmos.staking.v1beta1.MsgDelegate"

                val msgRevoke = MsgRevoke(granter, grantee, msgTypeUrl)
                val msgBytes = kotlinx.serialization.protobuf.ProtoBuf.encodeToByteArray(MsgRevoke.serializer(), msgRevoke)

                parsedMsgs.add(Pair(signedBlock.indexOf(block), ProtoAny("/cosmos.authz.v1beta1.MsgRevoke", msgBytes)))
            }

            if (parsedMsgs.isEmpty()) throw Exception("No recognized messages found in payload.")

            parsedMsgs.sortBy { it.first }
            val protoMessages = parsedMsgs.map { it.second }

            val modeInfo = ModeInfo(ModeInfoSingle(127))
            val signerInfo = SignerInfo(pubKeyAny, modeInfo, sequence)
            val authInfo = AuthInfo(listOf(signerInfo), fee)

            val authInfoBytes = kotlinx.serialization.protobuf.ProtoBuf.encodeToByteArray(AuthInfo.serializer(), authInfo)
            val txBody = TxBody(protoMessages, memo)
            val txBodyBytes = kotlinx.serialization.protobuf.ProtoBuf.encodeToByteArray(TxBody.serializer(), txBody)

            val txRaw = TxRaw(txBodyBytes, authInfoBytes, listOf(rawSignature))
            val txRawBytes = kotlinx.serialization.protobuf.ProtoBuf.encodeToByteArray(TxRaw.serializer(), txRaw)

            return android.util.Base64.encodeToString(txRawBytes, android.util.Base64.NO_WRAP)

        } catch (e: Exception) {
            throw Exception("Decoder Failed: ${e.message}")
        }
    }
}