package com.santoshi.crostakingdashboardyieldoptimizer

import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Autorenew
import androidx.compose.material.icons.filled.CardGiftcard
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Divider
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch

data class Wallet(val name: String, val address: String)

sealed class AppTab(val route: String, val title: String, val icon: ImageVector) {
    object Dashboard : AppTab("dashboard", "Dash", Icons.Default.Home)
    object Bridge : AppTab("bridge", "Bridge", Icons.Default.SwapHoriz)
    object Stake : AppTab("stake", "Stake", Icons.Default.Lock)
    object Compound : AppTab("compound", "Restake", Icons.Default.Autorenew)
    object Lotto : AppTab("lotto", "Lotto", Icons.Default.CardGiftcard)
    object About : AppTab("about", "About", Icons.Default.Person)
}

// VERIFIABLE FIX: Global lock immune to Compose destruction
private var globalLastProcessedPayload: String? = null
// VERIFIABLE FIX: Global address cache
private var globalCachedAddress: String = ""
// VERIFIABLE FIX: Wallet state caches to prevent UI flashes/resets on tab switches
private var globalCachedBondedBalance: Double = 0.0
private var globalCachedActiveExpiration: String? = null
// VERIFIABLE FIX: Map-based price cache prevents USD/EUR mismatch and CoinGecko rate-limiting
private val globalCroPrices = mutableMapOf<String, Double>()
private var globalCachedApr: Double = 0.0

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    // Your existing main app content
                    MainScreen()

                    // NEW: Mount the legal popup here.
                    // It will evaluate its own "has_accepted_terms" logic to decide whether to show or hide.
                    LegalConsentDialog()
                }
            }
        }
    }
}

@Composable
fun MainScreen() {
    val wcEngine = remember { WalletConnectEngine() }
    var currentTab by remember { mutableStateOf<AppTab>(AppTab.Dashboard) }

    val tabs = listOf(
        AppTab.Dashboard,
        AppTab.Bridge,
        AppTab.Stake,
        AppTab.Compound,
        AppTab.Lotto,
        AppTab.About
    )

    Scaffold(
        bottomBar = {
            NavigationBar(
                containerColor = MaterialTheme.colorScheme.surfaceVariant
            ) {
                tabs.forEach { tab ->
                    NavigationBarItem(
                        icon = { Icon(tab.icon, contentDescription = tab.title) },
                        label = { Text(tab.title, fontSize = 10.sp, maxLines = 1) },
                        selected = currentTab == tab,
                        onClick = { currentTab = tab },
                        colors = NavigationBarItemDefaults.colors(
                            selectedIconColor = Color(0xFF1E88E5),
                            selectedTextColor = Color(0xFF1E88E5),
                            unselectedIconColor = Color.Gray,
                            unselectedTextColor = Color.Gray
                        )
                    )
                }
            }
        }
    ) { innerPadding ->
        Box(modifier = Modifier.padding(innerPadding).fillMaxSize()) {
            when (currentTab) {
                AppTab.Dashboard -> PowerDashboardScreen()
                AppTab.Bridge -> BridgeScreen()
                AppTab.Stake -> StakeScreen(CryptoEngine, wcEngine)
                AppTab.Compound -> CompoundScreen(CryptoEngine, wcEngine)
                AppTab.Lotto -> LottoScreen()
                AppTab.About -> AboutScreen(CryptoEngine, wcEngine)
            }
        }
    }
}

@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
fun LegalConsentDialog() {
    val context = androidx.compose.ui.platform.LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    val prefs = remember { context.getSharedPreferences("NodePrefs", android.content.Context.MODE_PRIVATE) }

    var showDialog by remember { mutableStateOf(!prefs.getBoolean("has_accepted_terms", false)) }
    var isChecked by remember { mutableStateOf(false) }
    val scrollState = rememberScrollState()

    // State to hold the dynamic text
    var tosText by remember { mutableStateOf("Loading Terms of Service...") }
    var privacyText by remember { mutableStateOf("Loading Privacy Policy...") }

    // Direct export URLs
    val tosUrl = "https://docs.google.com/document/d/1xlaQwGio8zvYBTBBnkuWxp5nqZ1y4N-yzDyZtUyfBt4/export?format=txt"
    val privacyUrl = "https://docs.google.com/document/d/1svKK5IQl1vfiS8tuySh3_ZlVzasWiOyicB7eyagMNr4/export?format=txt"

    // Hardcoded Fallbacks (Paste your actual text here as the safety net)
    val fallbackTos = "FALLBACK TERMS OF SERVICE...\n\nLast Updated: April 30, 2026\n" +
            "1. Acceptance of Terms By accessing or using the CRO Staking Dashboard & Yield Optimizer (\"the App\"), you agree to be bound by these Terms of Service. If you do not agree, do not use the App.\n" +
            "2. Nature of the Service (Non-Custodial) The App is a strictly non-custodial, read-only interface and transaction payload generator. We do not have access to, nor do we control, your funds, private keys, or seed phrases. All transactions are authorized and signed externally via your independent third-party wallet.\n" +
            "3. No Financial Advice & Data Accuracy All information provided within the App—including but not limited to balance estimations, fiat conversions, APR/APY rates, and compound calculator projections—is for informational purposes only and does not constitute financial, investment, or tax advice.\n" +
            "Estimations: Yields, rewards, and fiat values are estimates derived from public third-party APIs (e.g., CoinGecko) and blockchain nodes. We do not guarantee the accuracy, completeness, or timeliness of this data.\n" +
            "Calculators: The Compound Calculator illustrates a mathematical best-case scenario and does not guarantee future returns.\n" +
            "4. Blockchain & Smart Contract Risks By using the App to interact with the Cronos POS network (including delegating, compounding, or issuing Authz grants for REStake), you acknowledge that you are interacting directly with decentralized blockchain protocols.\n" +
            "You assume all risks associated with blockchain technology, including network congestion, fluctuating gas fees, node downtime, and validator slashing events.\n" +
            "We are not responsible for any loss of funds resulting from failed transactions, incorrect network fees, or external wallet errors.\n" +
            "5. Limitation of Liability The App is provided on an \"AS IS\" and \"AS AVAILABLE\" basis, without warranties of any kind. To the maximum extent permitted by law, the developer of the App shall not be liable for any direct, indirect, incidental, or consequential damages resulting from your use of, or inability to use, the App, including but not limited to financial losses or data inaccuracies."
    val fallbackPrivacy = "FALLBACK PRIVACY POLICY...\n\nLast Updated: April 30, 2026\n" +
            "1. Introduction\n" +
            "Cronos (CRO) Staking Dashboard and Yield Optimizer (\"the App\") is a non-custodial portfolio tracker and decentralized application interface built for the Cronos POS network. We prioritize your digital privacy and security above all else. This policy outlines exactly what data is processed and how it is handled within the App.\n" +
            "2. No Collection of Private Cryptographic Data\n" +
            "The App is built on a strict non-custodial architecture.\n" +
            "Important: We DO NOT request, collect, store, or transmit your private keys, seed phrases, passwords, or keystore files.\n" +
            "All transaction signatures occur strictly and securely within your external, third-party wallet application (e.g., Crypto.com Onchain Wallet, Keplr, Cosmostation). The App acts solely as a dashboard and payload generator.\n" +
            "3. Data We Process (Local Storage Only)\n" +
            "To provide portfolio tracking and history synchronization, the App processes public blockchain data. All user-specific data is stored strictly locally on your device.\n" +
            "Public Wallet Addresses: If you save a wallet in the App, the public address and your custom display name are stored locally on your device's internal storage (via Android SharedPreferences).\n" +
            "Transaction History: Calculated data regarding your staking rewards, baselines, and balances are stored locally on your device to significantly improve loading times and reduce network strain.\n" +
            "Your data is never uploaded to our servers, sold, or shared with third parties. Clearing the App's storage data or uninstalling the App will permanently and irreversibly delete this information from your device.\n" +
            "4. Third-Party APIs and Services\n" +
            "The App functions as an aggregator, fetching live, read-only data from public sources to provide you with an accurate dashboard:\n" +
            "Public Blockchain Nodes: Fetches balances, staking delegations, and transaction histories from public Cronos POS REST and RPC endpoints.\n" +
            "CoinGecko API: Fetches current CRO market price data strictly for fiat (USD/EUR) estimations.\n" +
            "WalletConnect (Reown): Utilized to establish a secure, encrypted, peer-to-peer bridge between the App and your installed mobile wallet.\n" +
            "5. Analytics and Tracking\n" +
            "We believe in absolute privacy. The App does not use internal tracking software, product analytics frameworks (such as Google Analytics), or third-party advertising SDKs. We do not track, record, or monitor your usage behavior.\n" +
            "6. Children’s Privacy\n" +
            "The App is not intended for use by individuals under the age of 18. We do not knowingly collect any data from minors. If you are under 18, please do not use this App.\n" +
            "7. Changes to This Privacy Policy\n" +
            "We may update our Privacy Policy from time to time to reflect new features or regulatory requirements. We will notify you of any changes by updating the \"Last Updated\" date at the very top of this policy.\n" +
            "8. Contact Us\n" +
            "If you have any questions or concerns about this Privacy Policy, the App's architecture, or our security practices, please contact the developer directly at:\n" +
            "Email: santoshi.crypto@ethermail.io\n" +
            "Telegram: @pmbrsantos"

    LaunchedEffect(Unit) {
        if (showDialog) {
            // Fetch TOS
            coroutineScope.launch(kotlinx.coroutines.Dispatchers.IO) {
                try {
                    val result = java.net.URL(tosUrl).readText()
                    if (result.isNotBlank()) tosText = result else tosText = fallbackTos
                } catch (e: Exception) {
                    tosText = fallbackTos
                }
            }

            // Fetch Privacy Policy
            coroutineScope.launch(kotlinx.coroutines.Dispatchers.IO) {
                try {
                    val result = java.net.URL(privacyUrl).readText()
                    if (result.isNotBlank()) privacyText = result else privacyText = fallbackPrivacy
                } catch (e: Exception) {
                    privacyText = fallbackPrivacy
                }
            }
        }
    }

    if (showDialog) {
        AlertDialog(
            onDismissRequest = { /* Block dismissal to enforce compliance */ },
            properties = androidx.compose.ui.window.DialogProperties(
                dismissOnBackPress = false,
                dismissOnClickOutside = false
            ),
            title = {
                Text("Legal Agreements", fontWeight = androidx.compose.ui.text.font.FontWeight.Bold, fontSize = 20.sp)
            },
            text = {
                Column(modifier = Modifier.fillMaxWidth()) {
                    Text(
                        text = "Please review our Terms of Service and Privacy Policy before using the app.",
                        fontSize = 14.sp,
                        modifier = Modifier.padding(bottom = 12.dp)
                    )

                    Box(modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f, fill = false)
                        .heightIn(max = 400.dp)
                        .background(androidx.compose.material3.MaterialTheme.colorScheme.surfaceVariant, shape = androidx.compose.foundation.shape.RoundedCornerShape(8.dp))
                        .padding(12.dp)
                        .verticalScroll(scrollState)
                    ) {
                        Text(
                            text = """
                                $tosText
                                
                                ----------------------------------
                                
                                $privacyText
                            """.trimIndent(),
                            fontSize = 12.sp,
                            color = androidx.compose.material3.MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }

                    Spacer(modifier = Modifier.height(16.dp))

                    Row(
                        verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { isChecked = !isChecked }
                            .padding(vertical = 8.dp)
                    ) {
                        Checkbox(
                            checked = isChecked,
                            onCheckedChange = { isChecked = it }
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "I agree to the Terms of Service and Privacy Policy, and don't show this again.",
                            fontSize = 12.sp,
                            lineHeight = 16.sp
                        )
                    }
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        prefs.edit().putBoolean("has_accepted_terms", true).apply()
                        showDialog = false
                    },
                    enabled = isChecked,
                    colors = ButtonDefaults.buttonColors(containerColor = androidx.compose.ui.graphics.Color(0xFF1E88E5))
                ) {
                    Text("Accept & Continue")
                }
            }
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PowerDashboardScreen() {
    val context = androidx.compose.ui.platform.LocalContext.current
    val view = androidx.compose.ui.platform.LocalView.current
    val gson = remember { com.google.gson.Gson() }

    val prefs = remember { context.getSharedPreferences("NodePrefs", android.content.Context.MODE_PRIVATE) }

    val savedWalletsJson = prefs.getString("saved_wallets", "[]")
    val type = object : com.google.gson.reflect.TypeToken<List<Wallet>>() {}.type
    var walletList by remember { mutableStateOf(gson.fromJson<List<Wallet>>(savedWalletsJson, type)) }

    var selectedWallet by remember {
        val savedAddress = prefs.getString("selected_wallet_address", null)
        val restored = walletList.find { it.address == savedAddress }
        mutableStateOf(restored ?: walletList.firstOrNull())
    }
    var selectedCurrency by remember { mutableStateOf(prefs.getString("currency", "usd") ?: "usd") }
    val fiatSymbol = if (selectedCurrency == "usd") "$" else "€"

    var lastSyncDate by remember {
        mutableStateOf(prefs.getString("sync_date_${selectedWallet?.address}", "Never") ?: "Never")
    }

    var expanded by remember { mutableStateOf(false) }
    var showAddDialog by remember { mutableStateOf(false) }
    var showAllTimeYieldDialog by remember { mutableStateOf(false) }
    var showEstApyDialog by remember { mutableStateOf(false) }
    var showResetDialog by remember { mutableStateOf(false) }

    var showBreakdownDialog by remember { mutableStateOf(false) }
    var rewardBreakdown by remember { mutableStateOf<Map<String, Double>>(emptyMap()) }

    var newWalletName by remember { mutableStateOf("") }
    var newWalletAddress by remember { mutableStateOf("") }

    var baselineClaimed by remember { mutableStateOf(0.0) }
    var bondedBalance by remember { mutableStateOf(0.0) }
    var availableBalance by remember { mutableStateOf(0.0) }
    var unclaimedRewards by remember { mutableStateOf(0.0) }
    // VERIFIABLE FIX: Instantly load from global cache to survive CoinGecko rate limits and tab switches
    var apy by remember { mutableStateOf(globalCachedApr) }
    var croPrice by remember { mutableStateOf(globalCroPrices[selectedCurrency] ?: 0.0) }
    var accountAgeYears by remember { mutableStateOf(1.0) }
    var isLoading by remember { mutableStateOf(false) }

    val syncState by SyncStateHolder.state.collectAsState()
    val isSyncing = syncState.isSyncing
    val syncProgress = syncState.progress
    val syncCurrentPage = syncState.currentPage
    val syncTotalPages = syncState.totalPages

    // Keep screen on while sync is running and Dashboard is visible
    LaunchedEffect(isSyncing) {
        view.keepScreenOn = isSyncing
    }

    // Show one-shot messages from the service
    LaunchedEffect(syncState.message) {
        syncState.message?.let {
            android.widget.Toast.makeText(context, it, android.widget.Toast.LENGTH_SHORT).show()
            SyncStateHolder.consumeMessage()
        }
    }

    // VERIFIABLE FIX: Automatically trigger a live data fetch the moment a background sync finishes
    LaunchedEffect(syncState.isSyncing, selectedWallet) {
        if (!syncState.isSyncing && selectedWallet?.address == syncState.walletAddress && syncState.walletAddress.isNotEmpty()) {
            selectedWallet?.let { wallet ->
                // 1. Pull the newly calculated rewards from the sync engine
                baselineClaimed = prefs.getString("baseline_${wallet.address}", "0.0")?.toDoubleOrNull() ?: 0.0
                lastSyncDate = prefs.getString("sync_date_${wallet.address}", "Never") ?: "Never"
                rewardBreakdown = mapOf(
                    "Manual Claims" to (prefs.getString("manual_claims_${wallet.address}", "0.0")?.toDoubleOrNull() ?: 0.0),
                    "Auto-Restake" to (prefs.getString("auto_restake_${wallet.address}", "0.0")?.toDoubleOrNull() ?: 0.0),
                    "Staking Auto-Claims" to (prefs.getString("auto_claims_${wallet.address}", "0.0")?.toDoubleOrNull() ?: 0.0),
                    "Validator Commissions" to (prefs.getString("validator_commissions_${wallet.address}", "0.0")?.toDoubleOrNull() ?: 0.0)
                )

                // 2. Fetch Live Price, APY, and Age so they don't get stuck at 0.0 / 1.0 after a Reset
                isLoading = true
                try {
                    bondedBalance = CryptoEngine.getBondedBalance(wallet.address)
                    unclaimedRewards = CryptoEngine.getUnclaimedRewards(wallet.address)
                    availableBalance = CryptoEngine.getAvailableBalance(wallet.address)

                    val price = CryptoEngine.getCroPrice(selectedCurrency)
                    if (price > 0.0) {
                        croPrice = price
                        globalCroPrices[selectedCurrency] = price
                    }

                    val liveApy = CryptoEngine.getLiveNetworkAPR()
                    if (liveApy > 0.0) {
                        apy = liveApy
                        globalCachedApr = liveApy
                    }

                    val freshAge = CryptoEngine.getAccountAgeInYears(wallet.address)
                    if (freshAge > 1.0) {
                        accountAgeYears = freshAge
                        prefs.edit().putString("account_age_${wallet.address}", freshAge.toString()).apply()
                    }
                } catch (e: Exception) {
                    // Fail silently to ensure the UI still shows the successfully synced rewards
                } finally {
                    isLoading = false
                }
            }
        }
    }

    val coroutineScope = rememberCoroutineScope()

    // State to track if terms are accepted
    var hasAcceptedTerms by remember { mutableStateOf(prefs.getBoolean("has_accepted_terms", false)) }

    // Listen for the exact moment the Legal Dialog saves the 'true' boolean
    androidx.compose.runtime.DisposableEffect(Unit) {
        val listener = android.content.SharedPreferences.OnSharedPreferenceChangeListener { sharedPreferences, key ->
            if (key == "has_accepted_terms") {
                hasAcceptedTerms = sharedPreferences.getBoolean(key, false)
            }
        }
        prefs.registerOnSharedPreferenceChangeListener(listener)
        onDispose {
            prefs.unregisterOnSharedPreferenceChangeListener(listener)
        }
    }

    val totalBalanceCro = bondedBalance + availableBalance + unclaimedRewards
    val totalBalanceFiat = totalBalanceCro * croPrice
    val totalRewardsCro = unclaimedRewards + baselineClaimed
    val totalRewardsFiat = totalRewardsCro * croPrice
    val claimedRewardsFiat = baselineClaimed * croPrice

    // NON-DESTRUCTIVE FETCH LOGIC: Waits for consent, then fetches data independently
    LaunchedEffect(selectedWallet, hasAcceptedTerms) {
        // Halt all network execution until the user clicks "Accept"
        if (!hasAcceptedTerms) return@LaunchedEffect

        // 1. INDEPENDENT GLOBAL FETCHES: These run flawlessly even if the Cronos Node lags or fails
        coroutineScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            try {
                val price = CryptoEngine.getCroPrice(selectedCurrency)
                if (price > 0.0) {
                    croPrice = price
                    globalCroPrices[selectedCurrency] = price
                }
            } catch (e: Exception) {}
        }

        coroutineScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            try {
                val liveApy = CryptoEngine.getLiveNetworkAPR()
                if (liveApy > 0.0) {
                    apy = liveApy
                    globalCachedApr = liveApy
                }
            } catch (e: Exception) {}
        }

        // 2. WALLET-SPECIFIC FETCHES: Safely isolated in their own try/catch
        selectedWallet?.let { wallet ->
            lastSyncDate = prefs.getString("sync_date_${wallet.address}", "Never") ?: "Never"
            baselineClaimed = prefs.getString("baseline_${wallet.address}", "0.0")?.toDoubleOrNull() ?: 0.0

            val reloadedManual = prefs.getString("manual_claims_${wallet.address}", "0.0")?.toDoubleOrNull() ?: 0.0
            val reloadedRestake = prefs.getString("auto_restake_${wallet.address}", "0.0")?.toDoubleOrNull() ?: 0.0
            val reloadedAutoClaims = prefs.getString("auto_claims_${wallet.address}", "0.0")?.toDoubleOrNull() ?: 0.0
            val reloadedCommissions = prefs.getString("validator_commissions_${wallet.address}", "0.0")?.toDoubleOrNull() ?: 0.0

            rewardBreakdown = mapOf(
                "Manual Claims" to reloadedManual,
                "Auto-Restake" to reloadedRestake,
                "Staking Auto-Claims" to reloadedAutoClaims,
                "Validator Commissions" to reloadedCommissions
            )

            // Instantly load safe value from cache
            val savedAge = prefs.getString("account_age_${wallet.address}", null)?.toDoubleOrNull() ?: 1.0
            accountAgeYears = savedAge

            isLoading = true
            try {
                bondedBalance = CryptoEngine.getBondedBalance(wallet.address)
                unclaimedRewards = CryptoEngine.getUnclaimedRewards(wallet.address)
                availableBalance = CryptoEngine.getAvailableBalance(wallet.address)

                val freshAge = CryptoEngine.getAccountAgeInYears(wallet.address)
                // Only overwrite if we received valid multi-year data, OR if no valid data existed previously
                if (freshAge > 1.0 || savedAge == 1.0) {
                    accountAgeYears = freshAge
                    prefs.edit().putString("account_age_${wallet.address}", freshAge.toString()).apply()
                } else {
                    accountAgeYears = savedAge
                }
            } catch (e: Exception) {
                // Network failed (Doze mode or Node timeout). Values remain intact from cache.
            } finally {
                isLoading = false
            }
        } ?: run {
            baselineClaimed = 0.0
            bondedBalance = 0.0
            availableBalance = 0.0
            unclaimedRewards = 0.0
            rewardBreakdown = emptyMap()
        }
    }

    if (showAddDialog) {
        AlertDialog(
            onDismissRequest = { showAddDialog = false },
            title = { Text("Add New Wallet") },
            text = {
                Column {
                    OutlinedTextField(value = newWalletName, onValueChange = { newWalletName = it }, label = { Text("Wallet Name/Alias") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                    Spacer(modifier = Modifier.height(8.dp))
                    OutlinedTextField(value = newWalletAddress, onValueChange = { newWalletAddress = it }, label = { Text("CRO Address") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                }
            },
            confirmButton = {
                Button(onClick = {
                    if (newWalletName.isNotBlank() && newWalletAddress.isNotBlank()) {
                        val newWallet = Wallet(newWalletName.trim(), newWalletAddress.trim())
                        val updatedList = walletList + newWallet
                        walletList = updatedList
                        selectedWallet = newWallet
                        prefs.edit().putString("selected_wallet_address", newWallet.address).apply()
                        prefs.edit().putString("saved_wallets", gson.toJson(updatedList)).apply()
                        newWalletName = ""
                        newWalletAddress = ""
                        showAddDialog = false
                    }
                }) { Text("Save") }
            },
            dismissButton = { TextButton(onClick = { showAddDialog = false }) { Text("Cancel") } }
        )
    }

    if (showResetDialog) {
        AlertDialog(
            onDismissRequest = { showResetDialog = false },
            title = { Text("Reset History?") },
            text = { Text("This will clear the saved synchronization data for this wallet and force a deep scan from the beginning on your next sync. Are you sure?") },
            confirmButton = {
                Button(onClick = {
                    selectedWallet?.let { wallet ->
                        prefs.edit()
                            .remove("baseline_${wallet.address}")
                            .remove("last_hash_${wallet.address}")
                            .remove("sync_date_${wallet.address}")
                            .remove("manual_claims_${wallet.address}")
                            .remove("auto_restake_${wallet.address}")
                            .remove("auto_claims_${wallet.address}")
                            .remove("validator_commissions_${wallet.address}")
                            .remove("account_age_${wallet.address}")
                            .apply()

                        baselineClaimed = 0.0
                        lastSyncDate = "Never"
                        rewardBreakdown = emptyMap()
                        accountAgeYears = 1.0
                    }
                    showResetDialog = false
                }, colors = ButtonDefaults.buttonColors(containerColor = Color.Red)) {
                    Text("Clear History")
                }
            },
            dismissButton = { TextButton(onClick = { showResetDialog = false }) { Text("Cancel") } }
        )
    }

    if (showAllTimeYieldDialog) {
        AlertDialog(
            onDismissRequest = { showAllTimeYieldDialog = false },
            title = { Text("About All-Time Yield") },
            text = {
                Text("This is calculated by dividing your All-Time Rewards by your current Staked Balance.\n\nPlease note: If you unstaked or moved tokens in the past this estimation will appear artificially high, and if you compound interests you'll get a lower percentage because your base (Staked Balance) will also increase.")
            },
            confirmButton = { TextButton(onClick = { showAllTimeYieldDialog = false }) { Text("Got It") } }
        )
    }

    if (showEstApyDialog) {
        AlertDialog(
            onDismissRequest = { showEstApyDialog = false },
            title = { Text("About Est. All-Time APY") },
            text = {
                Text("This is calculated by dividing your All-Time Rewards by your current Staked Balance, and then dividing by the number of years since your first reward transaction.\n\nPlease note: This estimation assumes you have never unstaked any CRO. If you have unstaked or moved tokens in the past, this percentage will appear artificially high.")
            },
            confirmButton = { TextButton(onClick = { showEstApyDialog = false }) { Text("Got It") } }
        )
    }

    if (isSyncing) {
        AlertDialog(
            onDismissRequest = { },
            title = { Text("Deep Syncing History...") },
            text = {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("Scanning the blockchain for every claim you've ever made.", fontSize = 14.sp, color = Color.Gray, modifier = Modifier.padding(bottom = 16.dp))
                    LinearProgressIndicator(progress = { syncProgress }, modifier = Modifier.fillMaxWidth())
                    Spacer(modifier = Modifier.height(8.dp))
                    Text("Page $syncCurrentPage of $syncTotalPages", fontWeight = FontWeight.Bold)
                }
            },
            confirmButton = {
                Button(onClick = {
                    SyncForegroundService.stop(context)
                }, colors = ButtonDefaults.buttonColors(containerColor = Color.Red)) {
                    Text("Stop & Save Current Progress")
                }
            }
        )
    }

    if (showBreakdownDialog) {
        AlertDialog(
            onDismissRequest = { showBreakdownDialog = false },
            title = { Text("Rewards Breakdown") },
            text = {
                Column {
                    val manual = rewardBreakdown["Manual Claims"] ?: 0.0
                    val restake = rewardBreakdown["Auto-Restake"] ?: 0.0
                    val autoClaims = rewardBreakdown["Staking Auto-Claims"] ?: 0.0
                    val commissions = rewardBreakdown["Validator Commissions"] ?: 0.0

                    Text("Manual Claims: ${String.format("%,.2f", manual)} CRO", modifier = Modifier.padding(vertical = 4.dp))
                    Text("Auto-Restake: ${String.format("%,.2f", restake)} CRO", modifier = Modifier.padding(vertical = 4.dp))
                    Text("Staking Auto-Claims: ${String.format("%,.2f", autoClaims)} CRO", modifier = Modifier.padding(vertical = 4.dp))

                    if (commissions > 0) {
                        Text("Commissions: ${String.format("%,.2f", commissions)} CRO", modifier = Modifier.padding(vertical = 4.dp))
                    }
                }
            },
            confirmButton = { TextButton(onClick = { showBreakdownDialog = false }) { Text("Close") } }
        )
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp)
            .verticalScroll(rememberScrollState()),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {

        Row(modifier = Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            ExposedDropdownMenuBox(expanded = expanded, onExpandedChange = { expanded = !expanded }, modifier = Modifier.weight(1f)) {
                OutlinedTextField(
                    value = selectedWallet?.name ?: "Select a Wallet", onValueChange = {}, readOnly = true, label = { Text("Active Wallet") },
                    trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
                    colors = ExposedDropdownMenuDefaults.outlinedTextFieldColors(), modifier = Modifier.menuAnchor().fillMaxWidth()
                )
                Box(modifier = Modifier.weight(1f)) {
                    ExposedDropdownMenu(
                        expanded = expanded,
                        onDismissRequest = { expanded = false }) {
                        walletList.forEach { wallet ->
                            DropdownMenuItem(
                                text = { Text("${wallet.name} (${wallet.address.take(8)}...)") },
                                onClick = {
                                    selectedWallet = wallet
                                    prefs.edit().putString("selected_wallet_address", wallet.address).apply()
                                    expanded = false
                                }
                            )
                        }

                        Divider(modifier = Modifier.padding(vertical = 4.dp))

                        DropdownMenuItem(
                            text = { Text("➕ Add New Wallet", color = Color(0xFF1E88E5), fontWeight = FontWeight.Bold) },
                            onClick = { expanded = false; showAddDialog = true }
                        )

                        if (selectedWallet != null) {
                            DropdownMenuItem(
                                text = { Text("🗑️ Delete Current Wallet", color = Color.Red, fontWeight = FontWeight.Bold) },
                                onClick = {
                                    selectedWallet?.let { walletToRemove ->
                                        val updatedList = walletList.filter { it.address != walletToRemove.address }
                                        walletList = updatedList

                                        prefs.edit()
                                            .putString("saved_wallets", gson.toJson(updatedList))
                                            .apply()

                                        prefs.edit()
                                            .remove("baseline_${walletToRemove.address}")
                                            .remove("last_hash_${walletToRemove.address}")
                                            .remove("sync_date_${walletToRemove.address}")
                                            .remove("manual_claims_${walletToRemove.address}")
                                            .remove("auto_restake_${walletToRemove.address}")
                                            .remove("auto_claims_${walletToRemove.address}")
                                            .remove("validator_commissions_${walletToRemove.address}")
                                            .remove("account_age_${walletToRemove.address}")
                                            .apply()

                                        selectedWallet = updatedList.firstOrNull()
                                        prefs.edit().putString("selected_wallet_address", updatedList.firstOrNull()?.address).apply()
                                        expanded = false
                                    }
                                }
                            )
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.width(8.dp))

            OutlinedButton(
                onClick = {
                    val newCurrency = if (selectedCurrency == "usd") "eur" else "usd"
                    selectedCurrency = newCurrency
                    prefs.edit().putString("currency", newCurrency).apply()

                    // Instantly load from cache to prevent UI lag
                    croPrice = globalCroPrices[newCurrency] ?: 0.0

                    coroutineScope.launch {
                        try {
                            val price = CryptoEngine.getCroPrice(newCurrency)
                            if (price > 0.0) {
                                croPrice = price
                                globalCroPrices[newCurrency] = price
                            }
                        } catch (e: Exception) {}
                    }
                },
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 0.dp),
                modifier = Modifier.height(36.dp),
                colors = ButtonDefaults.outlinedButtonColors(contentColor = Color(0xFF1E88E5))
            ) {
                Text(if (selectedCurrency == "usd") "USD" else "EUR", fontWeight = FontWeight.Bold)
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        Button(
            onClick = {
                selectedWallet?.let { wallet ->
                    isLoading = true
                    coroutineScope.launch {
                        try {
                            bondedBalance = CryptoEngine.getBondedBalance(wallet.address)
                            unclaimedRewards = CryptoEngine.getUnclaimedRewards(wallet.address)
                            availableBalance = CryptoEngine.getAvailableBalance(wallet.address)

                            val price = CryptoEngine.getCroPrice(selectedCurrency)
                            if (price > 0.0) {
                                croPrice = price
                                globalCroPrices[selectedCurrency] = price
                            }

                            val liveApy = CryptoEngine.getLiveNetworkAPR()
                            if (liveApy > 0.0) {
                                apy = liveApy
                                globalCachedApr = liveApy
                            }

                            val freshAge = CryptoEngine.getAccountAgeInYears(wallet.address)
                            val savedAge = prefs.getString("account_age_${wallet.address}", null)?.toDoubleOrNull() ?: 1.0
                            if (freshAge > 1.0 || savedAge == 1.0) {
                                accountAgeYears = freshAge
                                prefs.edit().putString("account_age_${wallet.address}", freshAge.toString()).apply()
                            } else {
                                accountAgeYears = savedAge
                            }
                        } catch (e: Exception) {
                            // Suppress exception to keep UI intact
                        } finally {
                            isLoading = false
                        }
                    }
                }
            },
            modifier = Modifier.fillMaxWidth(), enabled = selectedWallet != null,
            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF1E88E5))
        ) { Text(if (isLoading) "Syncing Live Data..." else "Refresh Dashboard") }

        Spacer(modifier = Modifier.height(20.dp))

        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text("TOTAL BALANCE", color = Color.Gray, fontSize = 12.sp)
            Text("${String.format("%,.2f", totalBalanceCro)} CRO", fontSize = 26.sp, fontWeight = FontWeight.Bold)
            Text("$fiatSymbol${String.format("%,.2f", totalBalanceFiat)} ${selectedCurrency.uppercase()}", color = Color(0xFF1E88E5), fontSize = 18.sp)
        }

        Spacer(modifier = Modifier.height(20.dp))

        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Column(modifier = Modifier.weight(1f)) {
                GridItem(
                    title = "AVAILABLE BALANCE",
                    mainText = "${String.format("%,.2f", availableBalance)} CRO",
                    subText = "$fiatSymbol${String.format("%,.2f", availableBalance * croPrice)}"
                )
                Spacer(modifier = Modifier.height(16.dp))

                GridItem(
                    "PENDING REWARDS",
                    "${String.format("%,.4f", unclaimedRewards)} CRO",
                    "$fiatSymbol${String.format("%,.2f", unclaimedRewards * croPrice)}"
                )

                Spacer(modifier = Modifier.height(20.dp))

                Column(horizontalAlignment = Alignment.Start) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("CLAIMED REWARDS", color = Color.Gray, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                        if (rewardBreakdown.isNotEmpty()) {
                            Spacer(modifier = Modifier.width(4.dp))
                            Icon(
                                imageVector = androidx.compose.material.icons.Icons.Default.Info,
                                contentDescription = "Breakdown",
                                tint = Color.Gray,
                                modifier = Modifier
                                    .size(14.dp)
                                    .clickable { showBreakdownDialog = true }
                            )
                        }
                    }
                    Text("${String.format("%,.2f", baselineClaimed)} CRO", fontSize = 18.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 4.dp))
                    Text("$fiatSymbol${String.format("%,.2f", claimedRewardsFiat)}", color = Color(0xFF1E88E5), fontSize = 14.sp)

                    Row(
                        modifier = Modifier.padding(top = 12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Button(
                            onClick = {
                                selectedWallet?.let { wallet ->
                                    if (isSyncing) return@Button
                                    // Ask for POST_NOTIFICATIONS on Android 13+ (optional but recommended)
                                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                                        val granted = androidx.core.content.ContextCompat.checkSelfPermission(
                                            context, android.Manifest.permission.POST_NOTIFICATIONS
                                        ) == android.content.pm.PackageManager.PERMISSION_GRANTED
                                        if (!granted && context is android.app.Activity) {
                                            androidx.core.app.ActivityCompat.requestPermissions(
                                                context, arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), 1001
                                            )
                                        }
                                    }
                                    SyncForegroundService.start(context, wallet.address)
                                }
                            },
                            enabled = !isSyncing,
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF1E88E5)),
                            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 0.dp),
                            modifier = Modifier.height(32.dp)
                        ) {
                            Icon(androidx.compose.material.icons.Icons.Default.Refresh, contentDescription = "Sync", modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("Sync", fontSize = 12.sp)
                        }

                        Spacer(modifier = Modifier.width(8.dp))

                        OutlinedButton(
                            onClick = { showResetDialog = true },
                            enabled = !isSyncing,
                            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 0.dp),
                            modifier = Modifier.height(32.dp),
                            border = androidx.compose.foundation.BorderStroke(1.dp, Color.Red.copy(alpha = 0.5f)),
                            colors = ButtonDefaults.outlinedButtonColors(contentColor = Color.Red)
                        ) {
                            Icon(androidx.compose.material.icons.Icons.Default.Delete, contentDescription = "Reset", modifier = Modifier.size(16.dp))
                        }
                    }
                    Text("Last Sync: $lastSyncDate", color = Color.Gray, fontSize = 10.sp, modifier = Modifier.padding(top = 8.dp))
                }
            }

            Column(modifier = Modifier.weight(1f)) {
                GridItem(
                    title = "STAKED BALANCE",
                    mainText = "${String.format("%,.2f", bondedBalance)} CRO",
                    subText = "$fiatSymbol${String.format("%,.2f", bondedBalance * croPrice)}"
                )
                Spacer(modifier = Modifier.height(24.dp))

                GridItem("ESTIMATED APR", "${String.format("%.2f", apy)}%", "Live Network Rate")
                Spacer(modifier = Modifier.height(24.dp))
                GridItem("ALL-TIME REWARDS", "${String.format("%,.2f", totalRewardsCro)} CRO", "$fiatSymbol${String.format("%,.2f", totalRewardsFiat)}")
            }
        }

        Spacer(modifier = Modifier.height(24.dp))
        Divider(color = Color.DarkGray, thickness = 1.dp)
        Spacer(modifier = Modifier.height(16.dp))

        val allTimeYield = if (bondedBalance > 0) (totalRewardsCro / bondedBalance) * 100.0 else 0.0
        val estAllTimeApy = if (accountAgeYears > 0) allTimeYield / accountAgeYears else 0.0

        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Column(modifier = Modifier.weight(1f), horizontalAlignment = Alignment.Start) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("ALL-TIME YIELD", color = Color.Gray, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                    Spacer(modifier = Modifier.width(4.dp))
                    Icon(
                        imageVector = androidx.compose.material.icons.Icons.Default.Info,
                        contentDescription = "Info",
                        tint = Color.Gray,
                        modifier = Modifier
                            .size(14.dp)
                            .clickable { showAllTimeYieldDialog = true }
                    )
                }
                Text("${String.format("%,.2f", allTimeYield)}%", fontSize = 18.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 4.dp))
                Text("Total ROI", color = Color(0xFF1E88E5), fontSize = 14.sp, modifier = Modifier.padding(top = 2.dp))
            }

            Column(modifier = Modifier.weight(1f), horizontalAlignment = Alignment.Start) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("EST. ALL-TIME APY", color = Color.Gray, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                    Spacer(modifier = Modifier.width(4.dp))
                    Icon(
                        imageVector = androidx.compose.material.icons.Icons.Default.Info,
                        contentDescription = "Info",
                        tint = Color.Gray,
                        modifier = Modifier
                            .size(14.dp)
                            .clickable { showEstApyDialog = true }
                    )
                }
                Text("${String.format("%,.2f", estAllTimeApy)}%", fontSize = 18.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 4.dp))
                Text("With Compounding", color = Color(0xFF1E88E5), fontSize = 14.sp, modifier = Modifier.padding(top = 2.dp))
            }
        }
    }
}

@Composable
fun GridItem(title: String, mainText: String, subText: String) {
    Column(horizontalAlignment = Alignment.Start) {
        Text(title, color = Color.Gray, fontSize = 11.sp, fontWeight = FontWeight.Bold)
        Text(mainText, fontSize = 18.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 4.dp))
        if (subText.isNotEmpty()) {
            Text(subText, color = Color(0xFF1E88E5), fontSize = 14.sp, modifier = Modifier.padding(top = 2.dp))
        }
    }
}

@Composable
fun StakeScreen(engine: CryptoEngine, wcEngine: WalletConnectEngine) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val coroutineScope = rememberCoroutineScope()

    val wcState by wcEngine.walletState.collectAsState()

    val validatorAddress = "crocncl1s078nr9kp4ulsxgnsasnr3k6zg5q9erps348eg"

    var availableBalance by remember { mutableStateOf(0.0) }
    var unclaimedRewards by remember { mutableStateOf(0.0) }
    var inputAmount by remember { mutableStateOf("") }

    var otherDelegations by remember { mutableStateOf<List<Pair<String, Long>>>(emptyList()) }
    var isProcessing by remember { mutableStateOf(false) }

    var debugPayload by remember { mutableStateOf<String?>(null) }

    var baseApr by remember { mutableStateOf(globalCachedApr) }
    var croPrice by remember { mutableStateOf(0.0) }
    var selectedCurrency by remember { mutableStateOf("usd") }
    var fiatSymbol by remember { mutableStateOf("$") }

    // Fetch live APR and price on load
    LaunchedEffect(Unit) {
        val prefs = context.getSharedPreferences("NodePrefs", android.content.Context.MODE_PRIVATE)
        selectedCurrency = prefs.getString("currency", "usd") ?: "usd"
        fiatSymbol = if (selectedCurrency == "usd") "$" else "€"
        croPrice = globalCroPrices[selectedCurrency] ?: 0.0

        coroutineScope.launch {
            try {
                val apr = CryptoEngine.getLiveNetworkAPR()
                if (apr > 0.0) {
                    baseApr = apr
                    globalCachedApr = apr
                }
                val price = CryptoEngine.getCroPrice(selectedCurrency)
                if (price > 0.0) {
                    croPrice = price
                    globalCroPrices[selectedCurrency] = price
                }
            } catch (e: Exception) {}
        }
    }

    LaunchedEffect(Unit) {
        wcEngine.walletEvents.collect { event: WalletEvent ->
            if (event is WalletEvent.TransactionRejected) {
                android.widget.Toast.makeText(context, "Wallet: ${event.message}", android.widget.Toast.LENGTH_LONG).show()
                isProcessing = false
            }
        }
    }

    LaunchedEffect(wcState) {
        if (wcState is WalletState.Connected) {
            val address = (wcState as WalletState.Connected).address
            globalCachedAddress = address
            isProcessing = true

            coroutineScope.launch {
                try {
                    availableBalance = CryptoEngine.getAvailableBalance(address)
                } catch (e: Exception) {
                    availableBalance = 0.0
                }
            }

            coroutineScope.launch {
                try {
                    unclaimedRewards = CryptoEngine.getUnclaimedRewards(address)
                } catch (e: Exception) {
                    unclaimedRewards = 0.0
                } finally {
                    isProcessing = false
                }
            }

            coroutineScope.launch {
                try {
                    otherDelegations = CryptoEngine.getOtherDelegations(address, validatorAddress)
                } catch (e: Exception) {
                    otherDelegations = emptyList()
                }
            }
        }
    }

    LaunchedEffect(wcState) {
        if (wcState is WalletState.SignatureReceived) {
            val payloadJson = (wcState as WalletState.SignatureReceived).signaturePayload

            if (payloadJson == globalLastProcessedPayload) return@LaunchedEffect
            globalLastProcessedPayload = payloadJson

            coroutineScope.launch {
                isProcessing = true
                try {
                    val txBytesBase64 = CryptoEngine.encodeAminoToProtobuf(payloadJson)

                    if (txBytesBase64.isNotEmpty()) {
                        val result = CryptoEngine.broadcastTransaction(txBytesBase64)

                        if (result.first) {
                            val txHash = result.second.removePrefix("Success: ")
                            android.widget.Toast.makeText(context, "Transaction broadcast! Verifying on-chain...", android.widget.Toast.LENGTH_LONG).show()

                            val verification = CryptoEngine.verifyTransaction(txHash)
                            if (verification.first) {
                                android.widget.Toast.makeText(context, "Transaction confirmed!", android.widget.Toast.LENGTH_LONG).show()
                                if (globalCachedAddress.isNotEmpty()) {
                                    availableBalance = CryptoEngine.getAvailableBalance(globalCachedAddress)
                                    unclaimedRewards = CryptoEngine.getUnclaimedRewards(globalCachedAddress)
                                    otherDelegations = CryptoEngine.getOtherDelegations(globalCachedAddress, validatorAddress)
                                }
                                inputAmount = ""
                            } else {
                                debugPayload = "TRANSACTION FAILED ON-CHAIN:\n\n${verification.second}\n\nTx Hash: $txHash"
                            }
                        } else {
                            debugPayload = "NODE REJECTION:\n\n${result.second}"
                        }
                    } else {
                        debugPayload = "SILENT FAILURE.\nRAW PAYLOAD:\n$payloadJson"
                    }
                } catch (e: Exception) {
                    debugPayload = "CRASH ERROR: ${e.message}\n\nRAW PAYLOAD:\n$payloadJson"
                } finally {
                    isProcessing = false
                }
            }
        }
    }

    LaunchedEffect(Unit) {
        if (globalCachedAddress.isNotEmpty()) {
            coroutineScope.launch {
                try {
                    availableBalance = CryptoEngine.getAvailableBalance(globalCachedAddress)
                } catch (e: Exception) {
                    availableBalance = 0.0
                }
            }
            coroutineScope.launch {
                try {
                    unclaimedRewards = CryptoEngine.getUnclaimedRewards(globalCachedAddress)
                } catch (e: Exception) {
                    unclaimedRewards = 0.0
                }
            }
            coroutineScope.launch {
                try {
                    otherDelegations = CryptoEngine.getOtherDelegations(globalCachedAddress, validatorAddress)
                } catch (e: Exception) {
                    otherDelegations = emptyList()
                }
            }
        }
    }

    Box(modifier = Modifier.fillMaxSize().padding(16.dp)) {
        if (wcState is WalletState.Disconnected || wcState is WalletState.Error) {
            Column(
                modifier = Modifier.align(Alignment.Center),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text("Native Cronos POS Staking", fontSize = 20.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(bottom = 16.dp))
                if (wcState is WalletState.Error) {
                    Text((wcState as WalletState.Error).message, color = Color.Red, fontSize = 12.sp, modifier = Modifier.padding(bottom = 16.dp))
                }
                Button(
                    onClick = {
                        wcEngine.connect { uri ->
                            // Force this entire block back onto the Main UI Thread
                            coroutineScope.launch(kotlinx.coroutines.Dispatchers.Main) {
                                try {
                                    val intent = android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse(uri))
                                    intent.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                                    context.startActivity(intent)
                                } catch (e: Exception) {
                                    android.widget.Toast.makeText(context, "No compatible wallet found.", android.widget.Toast.LENGTH_SHORT).show()
                                }
                            }
                        }
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF1E88E5))
                ) {
                    Text("Connect Wallet via Reown")
                }
            }
        } else if (wcState is WalletState.Connected || wcState is WalletState.SignatureReceived) {

            val delegatorAddress = if (wcState is WalletState.Connected) {
                (wcState as WalletState.Connected).address
            } else {
                globalCachedAddress
            }

            if (delegatorAddress.isEmpty()) return@Box

            Column(modifier = Modifier.fillMaxWidth()) {

                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    Column {
                        Text("CROnquerorsNode Staking", fontSize = 20.sp, fontWeight = FontWeight.Bold)
                        Text("Connected: ${delegatorAddress.take(8)}...${delegatorAddress.takeLast(4)}", color = Color.Gray, fontSize = 12.sp)
                    }
                    OutlinedButton(
                        onClick = { wcEngine.disconnect() },
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = Color.Red),
                        border = androidx.compose.foundation.BorderStroke(1.dp, Color.Red),
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp),
                        modifier = Modifier.height(32.dp)
                    ) {
                        Text("Disconnect", fontSize = 12.sp)
                    }
                }

                Spacer(modifier = Modifier.height(24.dp))

                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Column {
                        Text("Available to Stake", color = Color.Gray, fontSize = 12.sp)
                        Text("${String.format("%,.2f", availableBalance)} CRO", fontWeight = FontWeight.Bold, fontSize = 16.sp)
                    }
                    Column(horizontalAlignment = Alignment.End) {
                        Text("Pending Rewards", color = Color.Gray, fontSize = 12.sp)
                        Text("${String.format("%,.4f", unclaimedRewards)} CRO", fontWeight = FontWeight.Bold, fontSize = 16.sp, color = Color(0xFF1E88E5))
                    }
                }

                Spacer(modifier = Modifier.height(24.dp))

                OutlinedTextField(
                    value = inputAmount,
                    onValueChange = { inputAmount = it },
                    label = { Text("Amount to Stake (CRO)") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    trailingIcon = {
                        TextButton(onClick = {
                            val maxSafe = availableBalance - 1.0
                            inputAmount = if (maxSafe > 0) String.format(java.util.Locale.US, "%.6f", maxSafe) else "0"
                        }) {
                            Text("MAX", color = Color(0xFF1E88E5))
                        }
                    }
                )

                Button(
                    onClick = {
                        val amountCro = inputAmount.toDoubleOrNull() ?: 0.0
                        if (amountCro <= 0) return@Button

                        val amountBaseCro = (amountCro * 100_000_000).toLong().toString()

                        val msgDelegate = """
                    [{
                        "type": "cosmos-sdk/MsgDelegate",
                        "value": {
                            "amount": {
                                "amount": "$amountBaseCro",
                                "denom": "basecro"
                            },
                            "delegator_address": "$delegatorAddress",
                            "validator_address": "$validatorAddress"
                        }
                    }]
                    """.trimIndent()

                        executeTransaction(context, engine, wcEngine, delegatorAddress, msgDelegate, "Stake via Cronos (CRO) Staking Dashboard and Yield Optimizer", coroutineScope) {
                            isProcessing = it
                        }
                    },
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                    enabled = !isProcessing &&
                            (inputAmount.toDoubleOrNull() ?: 0.0) > 0 &&
                            (inputAmount.toDoubleOrNull() ?: 0.0) <= availableBalance,
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF1E88E5))
                ) {
                    Text(if (isProcessing) "Generating Payload..." else "Stake CRO")
                }
                // --- DYNAMIC REWARDS ESTIMATOR ---
                val amountCro = inputAmount.toDoubleOrNull() ?: 0.0
                if (amountCro > 0) {
                    val commission = 5.0 // CROnquerorsNode commission
                    val realApr = if (baseApr > 0) baseApr * (1.0 - (commission / 100.0)) else 0.0

                    val yearlyCro = amountCro * (realApr / 100.0)
                    val monthlyCro = yearlyCro / 12.0
                    val weeklyCro = yearlyCro / 52.0
                    val dailyCro = yearlyCro / 365.0

                    Spacer(modifier = Modifier.height(16.dp))
                    Card(modifier = Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
                        Column(modifier = Modifier.padding(16.dp)) {
                            Text("Estimated Staking Rewards", fontSize = 14.sp, color = Color.Gray, fontWeight = FontWeight.Bold)
                            if (baseApr > 0) {
                                Text("Based on ${String.format("%.2f", realApr)}% APR (After 5% Node Commission)", fontSize = 12.sp, color = Color.LightGray, modifier = Modifier.padding(bottom = 12.dp))
                            } else {
                                Text("Awaiting Live Network APR...", fontSize = 12.sp, color = Color(0xFFF57C00), modifier = Modifier.padding(bottom = 12.dp))
                            }

                            Row(modifier = Modifier.fillMaxWidth()) {
                                Box(modifier = Modifier.weight(1f)) {
                                    GridItem("DAILY", "+${String.format("%,.4f", dailyCro)} CRO", "$fiatSymbol${String.format("%,.2f", dailyCro * croPrice)}")
                                }
                                Box(modifier = Modifier.weight(1f)) {
                                    GridItem("WEEKLY", "+${String.format("%,.4f", weeklyCro)} CRO", "$fiatSymbol${String.format("%,.2f", weeklyCro * croPrice)}")
                                }
                            }
                            Spacer(modifier = Modifier.height(12.dp))
                            Row(modifier = Modifier.fillMaxWidth()) {
                                Box(modifier = Modifier.weight(1f)) {
                                    GridItem("MONTHLY", "+${String.format("%,.4f", monthlyCro)} CRO", "$fiatSymbol${String.format("%,.2f", monthlyCro * croPrice)}")
                                }
                                Box(modifier = Modifier.weight(1f)) {
                                    GridItem("YEARLY", "+${String.format("%,.4f", yearlyCro)} CRO", "$fiatSymbol${String.format("%,.2f", yearlyCro * croPrice)}")
                                }
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))
                androidx.compose.material3.Divider()
                Spacer(modifier = Modifier.height(16.dp))

                Button(
                    onClick = {
                        val msgWithdraw = """
                    [{
                        "type": "cosmos-sdk/MsgWithdrawDelegationReward",
                        "value": {
                            "delegator_address": "$delegatorAddress",
                            "validator_address": "$validatorAddress"
                        }
                    }]
                    """.trimIndent()

                        executeTransaction(context, engine, wcEngine, delegatorAddress, msgWithdraw, "Withdraw Rewards via Cronos (CRO) Staking Dashboard and Yield Optimizer", coroutineScope) {
                            isProcessing = it
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                    enabled = !isProcessing && unclaimedRewards > 0.0,
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFF57C00))
                ) {
                    Text(if (isProcessing) "Generating Payload..." else "Withdraw Rewards")
                }

                Spacer(modifier = Modifier.height(8.dp))

                Button(
                    onClick = {
                        val rewardBaseCro = (unclaimedRewards * 100_000_000).toLong().toString()

                        val msgRestake = """
                    [
                        {
                            "type": "cosmos-sdk/MsgWithdrawDelegationReward",
                            "value": {
                                "delegator_address": "$delegatorAddress",
                                "validator_address": "$validatorAddress"
                            }
                        },
                        {
                            "type": "cosmos-sdk/MsgDelegate",
                            "value": {
                                "amount": {
                                    "amount": "$rewardBaseCro",
                                    "denom": "basecro"
                                },
                                "delegator_address": "$delegatorAddress",
                                "validator_address": "$validatorAddress"
                            }
                        }
                    ]
                    """.trimIndent()

                        executeTransaction(context, engine, wcEngine, delegatorAddress, msgRestake, "Compound Rewards via Cronos (CRO) Staking Dashboard and Yield Optimizer", coroutineScope) {
                            isProcessing = it
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                    enabled = !isProcessing && unclaimedRewards > 0.0,
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF4CAF50))
                ) {
                    Text(if (isProcessing) "Generating Payload..." else "Restake (Compound) Rewards")
                }

                Spacer(modifier = Modifier.height(8.dp))

                Button(
                    onClick = {
                        val messages = otherDelegations.joinToString(",\n") { (srcValidator, amountBaseCro) ->
                            """
                        {
                            "type": "cosmos-sdk/MsgBeginRedelegate",
                            "value": {
                                "amount": {
                                    "amount": "$amountBaseCro",
                                    "denom": "basecro"
                                },
                                "delegator_address": "$delegatorAddress",
                                "validator_dst_address": "$validatorAddress",
                                "validator_src_address": "$srcValidator"
                            }
                        }
                        """.trimIndent()
                        }

                        val payloadArray = "[\n$messages\n]"

                        executeTransaction(context, engine, wcEngine, delegatorAddress, payloadArray, "Redelegate via Cronos (CRO) Staking Dashboard and Yield Optimizer", coroutineScope) {
                            isProcessing = it
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                    enabled = !isProcessing && otherDelegations.isNotEmpty(),
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF673AB7)) // Deep Purple to stand out
                ) {
                    val totalRivalCro = otherDelegations.sumOf { it.second } / 100_000_000.0
                    Text(if (isProcessing) "Generating Payload..." else "Redelegate All (~${String.format("%,.2f", totalRivalCro)} CRO) to CROnquerorsNode")
                }
            }
        }

        if (debugPayload != null) {
            AlertDialog(
                onDismissRequest = { debugPayload = null },
                title = { Text("Diagnostic Data", fontWeight = FontWeight.Bold) },
                text = {
                    androidx.compose.foundation.text.selection.SelectionContainer {
                        Text(
                            text = debugPayload ?: "",
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(max = 400.dp)
                                .verticalScroll(rememberScrollState()),
                            fontSize = 12.sp,
                            fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace
                        )
                    }
                },
                confirmButton = {
                    Button(onClick = { debugPayload = null }) {
                        Text("Close")
                    }
                }
            )
        }
    }
}

private fun executeTransaction(
    context: android.content.Context,
    engine: CryptoEngine,
    wcEngine: WalletConnectEngine,
    address: String,
    messages: String,
    memo: String,
    scope: kotlinx.coroutines.CoroutineScope,
    setProcessing: (Boolean) -> Unit
) {
    setProcessing(true)
    scope.launch {
        val accountState = CryptoEngine.getAccountState(address)

        if (accountState != null && accountState.second != "0") {

            scope.launch(kotlinx.coroutines.Dispatchers.Main) {
                android.widget.Toast.makeText(context, "Sequence [${accountState.second}] validated. Broadcasting to Relay...", android.widget.Toast.LENGTH_SHORT).show()
            }

            wcEngine.sendCosmosTransaction(
                delegatorAddress = address,
                messagesJsonArray = messages,
                memo = memo,
                accountNumber = accountState.first,
                sequence = accountState.second,
                onSuccess = {
                    scope.launch(kotlinx.coroutines.Dispatchers.Main) {
                        kotlinx.coroutines.delay(1500)

                        setProcessing(false)
                        try {
                            val intent = android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse("dfw://"))
                            intent.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                            context.startActivity(intent)
                        } catch (e: Exception) {}
                    }
                },
                onError = { errorMsg ->
                    setProcessing(false)
                    scope.launch(kotlinx.coroutines.Dispatchers.Main) {
                        android.widget.Toast.makeText(context, "Wallet Error: $errorMsg", android.widget.Toast.LENGTH_LONG).show()
                    }
                }
            )
        } else {
            setProcessing(false)
            scope.launch(kotlinx.coroutines.Dispatchers.Main) {
                android.widget.Toast.makeText(context, "Node Error: Sequence evaluated to 0. The wallet will reject this.", android.widget.Toast.LENGTH_LONG).show()
            }
        }
    }
}

@Composable
fun DappLaunchpadScreen(
    title: String,
    description: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    buttons: @Composable () -> Unit
) {
    Column(
        modifier = Modifier.fillMaxSize().padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Icon(
            imageVector = icon,
            contentDescription = title,
            modifier = Modifier.size(64.dp),
            tint = Color(0xFF1E88E5)
        )

        Spacer(modifier = Modifier.height(16.dp))

        Text(title, fontSize = 24.sp, fontWeight = FontWeight.Bold)

        Spacer(modifier = Modifier.height(8.dp))

        Text(
            text = description,
            fontSize = 14.sp,
            color = Color.Gray,
            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
            modifier = Modifier.padding(horizontal = 16.dp)
        )

        Spacer(modifier = Modifier.height(32.dp))

        buttons()
    }
}

@Composable
fun Web3DappScreen(targetUrl: String) {
    val context = androidx.compose.ui.platform.LocalContext.current

    Box(modifier = Modifier.fillMaxSize()) {
        androidx.compose.ui.viewinterop.AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { ctx ->
                android.webkit.WebView(ctx).apply {
                    settings.javaScriptEnabled = true
                    settings.domStorageEnabled = true
                    settings.databaseEnabled = true
                    settings.useWideViewPort = true
                    settings.loadWithOverviewMode = true
                    settings.mixedContentMode = android.webkit.WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
                    settings.javaScriptCanOpenWindowsAutomatically = true
                    settings.setSupportMultipleWindows(true)
                    settings.userAgentString = "Mozilla/5.0 (Linux; Android 13; Mobile; SM-S918B) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/112.0.0.0 Mobile Safari/537.36"
                    android.webkit.CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)

                    webViewClient = object : android.webkit.WebViewClient() {
                        override fun shouldOverrideUrlLoading(view: android.webkit.WebView?, request: android.webkit.WebResourceRequest?): Boolean {
                            val url = request?.url?.toString() ?: return false
                            if (url.startsWith("http://") || url.startsWith("https://")) return false

                            if (url.startsWith("intent:") || url.startsWith("wc:") || url.startsWith("keplrwallet:") || url.startsWith("cosmostation:") || url.startsWith("dfw:")) {
                                try {
                                    val intent = android.content.Intent.parseUri(url, android.content.Intent.URI_INTENT_SCHEME)
                                    intent.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)

                                    if (intent.resolveActivity(context.packageManager) != null) {
                                        context.startActivity(intent)
                                        return true
                                    }

                                    val fallbackUrl = intent.getStringExtra("browser_fallback_url")
                                    if (fallbackUrl != null) {
                                        view?.loadUrl(fallbackUrl)
                                        return true
                                    }

                                    val targetPackage = intent.`package`
                                    if (targetPackage != null) {
                                        val marketIntent = android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse("market://details?id=$targetPackage"))
                                        marketIntent.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                                        context.startActivity(marketIntent)
                                        return true
                                    }
                                    return true
                                } catch (e: Exception) {
                                    return true
                                }
                            }
                            return false
                        }
                    }

                    webChromeClient = object : android.webkit.WebChromeClient() {
                        override fun onCreateWindow(view: android.webkit.WebView?, isDialog: Boolean, isUserGesture: Boolean, resultMsg: android.os.Message?): Boolean {
                            val newWebView = android.webkit.WebView(context)
                            newWebView.webViewClient = object : android.webkit.WebViewClient() {
                                override fun shouldOverrideUrlLoading(view: android.webkit.WebView?, request: android.webkit.WebResourceRequest?): Boolean {
                                    val url = request?.url?.toString() ?: return false
                                    if (url.startsWith("intent:") || url.startsWith("wc:") || url.startsWith("keplrwallet:") || url.startsWith("cosmostation:")) {
                                        try {
                                            val intent = android.content.Intent.parseUri(url, android.content.Intent.URI_INTENT_SCHEME)
                                            intent.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                                            context.startActivity(intent)
                                        } catch (e: Exception) {}
                                        return true
                                    }
                                    return false
                                }
                            }
                            val transport = resultMsg?.obj as? android.webkit.WebView.WebViewTransport
                            transport?.webView = newWebView
                            resultMsg?.sendToTarget()
                            return true
                        }
                    }
                    loadUrl(targetUrl)
                }
            }
        )
    }
}

// --------------------------------------------------------
// THE TAB ROUTING
// --------------------------------------------------------

@Composable
fun BridgeScreen() {
    val context = androidx.compose.ui.platform.LocalContext.current
    val clipboardManager = androidx.compose.ui.platform.LocalClipboardManager.current
    val targetUrl = "https://cronos.org/bridge/"

    DappLaunchpadScreen(
        title = "Cronos Bridge",
        description = "Bridge your assets seamlessly between the Cronos POS chain and other networks using the official Cronos Bridge.",
        icon = androidx.compose.material.icons.Icons.Default.SwapHoriz
    ) {
        Button(
            onClick = {
                clipboardManager.setText(androidx.compose.ui.text.AnnotatedString(targetUrl))
                android.widget.Toast.makeText(context, "URL copied! Please paste it in the Onchain Wallet browser.", android.widget.Toast.LENGTH_LONG).show()

                val intent = context.packageManager.getLaunchIntentForPackage("com.defi.wallet")
                if (intent != null) {
                    intent.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                    context.startActivity(intent)
                } else {
                    val playIntent = android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse("market://details?id=com.defi.wallet"))
                    playIntent.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                    context.startActivity(playIntent)
                }
            },
            modifier = Modifier.fillMaxWidth().height(48.dp),
            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF1E88E5))
        ) {
            Text("Open Onchain Wallet")
        }
    }
}

@Composable
fun CompoundScreen(engine: CryptoEngine = CryptoEngine, wcEngine: WalletConnectEngine) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val coroutineScope = rememberCoroutineScope()

    val wcState by wcEngine.walletState.collectAsState()

    // Verified CROnquerorsNode Data
    val botAddress = "cro1xmannxgcr6szlg9uh66mxxxxs80d0ae4rr9eus"
    val commission = 5.0
    val frequency = "Every day at 21:00"

    var selectedCurrency by remember { mutableStateOf("usd") }
    var fiatSymbol by remember { mutableStateOf("$") }

    // FIX: Initialize directly from the global cache so state survives tab switches
    var bondedBalance by remember { mutableStateOf(globalCachedBondedBalance) }
    var activeExpiration by remember { mutableStateOf(globalCachedActiveExpiration) }
    var baseApr by remember { mutableStateOf(globalCachedApr) }
    var croPrice by remember { mutableStateOf(0.0) }

    var isProcessing by remember { mutableStateOf(false) }
    var isFetchingStatus by remember { mutableStateOf(false) }
    var debugPayload by remember { mutableStateOf<String?>(null) }

    // Fetch Live Price and Global APY strictly on load
    LaunchedEffect(Unit) {
        val prefs = context.getSharedPreferences("NodePrefs", android.content.Context.MODE_PRIVATE)
        val cur = prefs.getString("currency", "usd") ?: "usd"
        selectedCurrency = cur
        fiatSymbol = if (cur == "usd") "$" else "€"

        croPrice = globalCroPrices[cur] ?: 0.0

        coroutineScope.launch {
            try {
                val price = CryptoEngine.getCroPrice(cur)
                if (price > 0.0) {
                    croPrice = price
                    globalCroPrices[cur] = price
                }
            } catch (e: Exception) {}
        }
        coroutineScope.launch {
            try {
                val apy = CryptoEngine.getLiveNetworkAPR()
                if (apy > 0.0) {
                    baseApr = apy
                    globalCachedApr = apy
                }
            } catch (e: Exception) {}
        }
    }

    // FIX: Trigger fetch on BOTH Connected and SignatureReceived states
    LaunchedEffect(wcState) {
        val address = when (wcState) {
            is WalletState.Connected -> (wcState as WalletState.Connected).address
            is WalletState.SignatureReceived -> globalCachedAddress
            else -> ""
        }

        if (address.isNotEmpty()) {
            globalCachedAddress = address
            // Only show loading indicator if we don't already have cached data
            if (activeExpiration == null) isFetchingStatus = true

            coroutineScope.launch {
                try {
                    val bal = CryptoEngine.getBondedBalance(address)
                    val exp = CryptoEngine.checkRestakeGrant(address, botAddress)

                    bondedBalance = bal
                    globalCachedBondedBalance = bal

                    activeExpiration = exp
                    globalCachedActiveExpiration = exp
                } catch (e: Exception) {
                } finally {
                    isFetchingStatus = false
                }
            }
        } else if (wcState is WalletState.Disconnected || wcState is WalletState.Error) {
            bondedBalance = 0.0
            activeExpiration = null
            globalCachedBondedBalance = 0.0
            globalCachedActiveExpiration = null
        }
    }

    LaunchedEffect(wcState) {
        if (wcState is WalletState.SignatureReceived) {
            val payloadJson = (wcState as WalletState.SignatureReceived).signaturePayload

            if (payloadJson == globalLastProcessedPayload) return@LaunchedEffect
            globalLastProcessedPayload = payloadJson

            coroutineScope.launch {
                isProcessing = true
                try {
                    val txBytesBase64 = CryptoEngine.encodeAminoToProtobuf(payloadJson)

                    if (txBytesBase64.isNotEmpty()) {
                        val result = CryptoEngine.broadcastTransaction(txBytesBase64)

                        if (result.first) {
                            val txHash = result.second.removePrefix("Success: ")
                            android.widget.Toast.makeText(context, "Transaction broadcast! Verifying on-chain...", android.widget.Toast.LENGTH_LONG).show()

                            val verification = CryptoEngine.verifyTransaction(txHash)
                            if (verification.first) {
                                android.widget.Toast.makeText(context, "Confirmed on-chain!", android.widget.Toast.LENGTH_LONG).show()

                                // FIX: Update caches instantly upon successful confirmation
                                if (globalCachedAddress.isNotEmpty()) {
                                    val updatedExp = CryptoEngine.checkRestakeGrant(globalCachedAddress, botAddress)
                                    activeExpiration = updatedExp
                                    globalCachedActiveExpiration = updatedExp
                                }
                            } else {
                                debugPayload = "TRANSACTION FAILED ON-CHAIN:\n\n${verification.second}\n\nTx Hash: $txHash"
                            }
                        } else {
                            debugPayload = "NODE REJECTION:\n\n${result.second}"
                        }
                    } else {
                        debugPayload = "SILENT FAILURE.\nRAW PAYLOAD:\n$payloadJson"
                    }
                } catch (e: Exception) {
                    debugPayload = "CRASH ERROR: ${e.message}\n\nRAW PAYLOAD:\n$payloadJson"
                } finally {
                    isProcessing = false
                }
            }
        }
    }

    val realApr = if (baseApr > 0) baseApr * (1.0 - (commission / 100.0)) else 0.0
    val realApy = if (realApr > 0) (Math.pow(1.0 + (realApr / 100.0) / 365.0, 365.0) - 1.0) * 100.0 else 0.0

    val delegatorAddress = when (wcState) {
        is WalletState.Connected -> (wcState as WalletState.Connected).address
        is WalletState.SignatureReceived -> globalCachedAddress
        else -> ""
    }

    Box(modifier = Modifier.fillMaxSize().padding(16.dp)) {
        Column(modifier = Modifier.fillMaxWidth().verticalScroll(rememberScrollState())) {

            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("CROnquerorsNode Restaking", fontSize = 18.sp, fontWeight = FontWeight.Bold, maxLines = 1)
                    if (delegatorAddress.isNotEmpty()) {
                        Text("Connected: ${delegatorAddress.take(8)}...${delegatorAddress.takeLast(4)}", color = Color.Gray, fontSize = 12.sp)
                    } else {
                        Text("Wallet not connected", color = Color.Gray, fontSize = 12.sp)
                    }
                }

                Spacer(modifier = Modifier.width(8.dp))

                if (wcState is WalletState.Connected || wcState is WalletState.SignatureReceived) {
                    OutlinedButton(
                        onClick = { wcEngine.disconnect() },
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = Color.Red),
                        border = androidx.compose.foundation.BorderStroke(1.dp, Color.Red),
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp),
                        modifier = Modifier.height(32.dp)
                    ) {
                        Text("Disconnect", fontSize = 12.sp)
                    }
                } else {
                    Button(
                        onClick = {
                            wcEngine.connect { uri ->
                                // Force this entire block back onto the Main UI Thread
                                coroutineScope.launch(kotlinx.coroutines.Dispatchers.Main) {
                                    try {
                                        val intent = android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse(uri))
                                        intent.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                                        context.startActivity(intent)
                                    } catch (e: Exception) {
                                        android.widget.Toast.makeText(context, "No compatible wallet found.", android.widget.Toast.LENGTH_SHORT).show()
                                    }
                                }
                            }
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF1E88E5)),
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp),
                        modifier = Modifier.height(32.dp)
                    ) {
                        Text("Connect", fontSize = 12.sp)
                    }
                }
            }

            Spacer(modifier = Modifier.height(24.dp))

            Card(modifier = Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(modifier = Modifier.fillMaxWidth()) {
                        Box(modifier = Modifier.weight(1f)) {
                            GridItem("APR", "${String.format("%.2f", realApr)}%", "After Commission")
                        }
                        Box(modifier = Modifier.weight(1f)) {
                            GridItem("APY", "${String.format("%.2f", realApy)}%", "Compounded Daily")
                        }
                    }
                    Spacer(modifier = Modifier.height(16.dp))
                    Row(modifier = Modifier.fillMaxWidth()) {
                        Box(modifier = Modifier.weight(1f)) {
                            GridItem("COMMISSION", "${String.format("%.0f", commission)}%", "")
                        }
                        Box(modifier = Modifier.weight(1f)) {
                            GridItem("FREQUENCY", frequency, "")
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            Card(modifier = Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text("REStake Status", fontSize = 14.sp, color = Color.Gray, fontWeight = FontWeight.Bold)

                    if (delegatorAddress.isEmpty()) {
                        Text("UNKNOWN", fontSize = 18.sp, fontWeight = FontWeight.Bold, color = Color.Gray, modifier = Modifier.padding(top = 4.dp))
                        Text("Connect wallet to view your grant status", fontSize = 12.sp, color = Color.LightGray)
                    } else if (isFetchingStatus) {
                        Text("Checking blockchain...", fontSize = 16.sp, modifier = Modifier.padding(top = 4.dp))
                    } else if (activeExpiration != null) {
                        Text("ACTIVE", fontSize = 18.sp, fontWeight = FontWeight.Bold, color = Color(0xFF4CAF50), modifier = Modifier.padding(top = 4.dp))
                        Text("Expires: $activeExpiration", fontSize = 12.sp, color = Color.LightGray)
                    } else {
                        Text("INACTIVE", fontSize = 18.sp, fontWeight = FontWeight.Bold, color = Color(0xFFF57C00), modifier = Modifier.padding(top = 4.dp))
                        Text("Bot is not authorized to compound", fontSize = 12.sp, color = Color.LightGray)
                    }

                    Spacer(modifier = Modifier.height(16.dp))

                    if (delegatorAddress.isEmpty()) {
                        Button(
                            onClick = {
                                wcEngine.connect { uri ->
                                    // Force this entire block back onto the Main UI Thread
                                    coroutineScope.launch(kotlinx.coroutines.Dispatchers.Main) {
                                        try {
                                            val intent = android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse(uri))
                                            intent.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                                            context.startActivity(intent)
                                        } catch (e: Exception) {
                                            android.widget.Toast.makeText(context, "No compatible wallet found.", android.widget.Toast.LENGTH_SHORT).show()
                                        }
                                    }
                                }
                            },
                            modifier = Modifier.fillMaxWidth(),
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF1E88E5))
                        ) { Text("Connect Wallet to Manage Grant") }
                    } else if (activeExpiration == null) {
                        Button(
                            onClick = {
                                val calendar = java.util.Calendar.getInstance()
                                calendar.add(java.util.Calendar.YEAR, 1) // Standard 1-year grant
                                val format = java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", java.util.Locale.US)
                                format.timeZone = java.util.TimeZone.getTimeZone("UTC")
                                val formattedExpiration = format.format(calendar.time)

                                val msgGrant = """
                                [{
                                    "type": "cosmos-sdk/MsgGrant",
                                    "value": {
                                        "grant": {
                                            "authorization": {
                                                "type": "cosmos-sdk/GenericAuthorization",
                                                "value": {
                                                    "msg": "/cosmos.staking.v1beta1.MsgDelegate"
                                                }
                                            },
                                            "expiration": "$formattedExpiration"
                                        },
                                        "grantee": "$botAddress",
                                        "granter": "$delegatorAddress"
                                    }
                                }]
                                """.trimIndent()

                                executeTransaction(context, engine, wcEngine, delegatorAddress, msgGrant, "Enable REStake via Cronos (CRO) Staking Dashboard and Yield Optimizer", coroutineScope) { isProcessing = it }
                            },
                            modifier = Modifier.fillMaxWidth(),
                            enabled = !isProcessing,
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF4CAF50))
                        ) { Text(if (isProcessing) "Generating Payload..." else "Enable Auto-Compound (1 Year)") }
                    } else {
                        OutlinedButton(
                            onClick = {
                                val msgRevoke = """
                                [{
                                    "type": "cosmos-sdk/MsgRevoke",
                                    "value": {
                                        "grantee": "$botAddress",
                                        "granter": "$delegatorAddress",
                                        "msg_type_url": "/cosmos.staking.v1beta1.MsgDelegate"
                                    }
                                }]
                                """.trimIndent()

                                executeTransaction(context, engine, wcEngine, delegatorAddress, msgRevoke, "Revoke REStake via Cronos (CRO) Staking Dashboard and Yield Optimizer", coroutineScope) { isProcessing = it }
                            },
                            modifier = Modifier.fillMaxWidth(),
                            enabled = !isProcessing,
                            colors = ButtonDefaults.outlinedButtonColors(contentColor = Color.Red),
                            border = androidx.compose.foundation.BorderStroke(1.dp, Color.Red)
                        ) { Text(if (isProcessing) "Generating Payload..." else "Disable & Revoke Grant") }
                    }
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            Card(modifier = Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text("Compound Calculator", fontSize = 14.sp, color = Color.Gray, fontWeight = FontWeight.Bold)
                    Text(if (delegatorAddress.isNotEmpty()) "Based on your ${String.format("%,.2f", bondedBalance)} CRO Staked" else "Connect wallet to calculate based on your staked balance", fontSize = 12.sp, color = Color.LightGray, modifier = Modifier.padding(bottom = 12.dp))

                    val yearlyCro = bondedBalance * (realApy / 100.0)
                    val monthlyCro = yearlyCro / 12.0
                    val weeklyCro = yearlyCro / 52.0
                    val dailyCro = yearlyCro / 365.0

                    Row(modifier = Modifier.fillMaxWidth()) {
                        Box(modifier = Modifier.weight(1f)) {
                            GridItem("DAILY", "+${String.format("%,.4f", dailyCro)} CRO", "$fiatSymbol${String.format("%,.2f", dailyCro * croPrice)}")
                        }
                        Box(modifier = Modifier.weight(1f)) {
                            GridItem("WEEKLY", "+${String.format("%,.4f", weeklyCro)} CRO", "$fiatSymbol${String.format("%,.2f", weeklyCro * croPrice)}")
                        }
                    }
                    Spacer(modifier = Modifier.height(12.dp))
                    Row(modifier = Modifier.fillMaxWidth()) {
                        Box(modifier = Modifier.weight(1f)) {
                            GridItem("MONTHLY", "+${String.format("%,.4f", monthlyCro)} CRO", "$fiatSymbol${String.format("%,.2f", monthlyCro * croPrice)}")
                        }
                        Box(modifier = Modifier.weight(1f)) {
                            GridItem("YEARLY", "+${String.format("%,.4f", yearlyCro)} CRO", "$fiatSymbol${String.format("%,.2f", yearlyCro * croPrice)}")
                        }
                    }
                }
            }
            Spacer(modifier = Modifier.height(32.dp))
        }

        if (debugPayload != null) {
            AlertDialog(
                onDismissRequest = { debugPayload = null },
                title = { Text("Diagnostic Data", fontWeight = FontWeight.Bold) },
                text = {
                    androidx.compose.foundation.text.selection.SelectionContainer {
                        Text(
                            text = debugPayload ?: "",
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(max = 400.dp)
                                .verticalScroll(rememberScrollState()),
                            fontSize = 12.sp,
                            fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace
                        )
                    }
                },
                confirmButton = {
                    Button(onClick = { debugPayload = null }) {
                        Text("Close")
                    }
                }
            )
        }
    }
}

@Composable
fun LottoScreen() {
    Web3DappScreen("https://lotto-cro.tedcrypto.io/my-prizes")
}

@Composable
fun AboutScreen(engine: CryptoEngine, wcEngine: WalletConnectEngine) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    val wcState by wcEngine.walletState.collectAsState()

    var validatorStats by remember { mutableStateOf<CryptoEngine.ValidatorStats?>(null) }

    // Separation of Roles: Operator vs. Standard Account
    val validatorAddress = "crocncl1s078nr9kp4ulsxgnsasnr3k6zg5q9erps348eg"
    val donationAddress = "cro14r7dvwst88gzeuljf9p8ce2muwv8hpm6l2m9rg"

    // Let the effect handle initial load to ensure strict symbol/value mapping
    var croPrice by remember { mutableStateOf(0.0) }
    var isProcessing by remember { mutableStateOf(false) }

    val scrollState = rememberScrollState()

    val isConnected = wcState is WalletState.Connected || wcState is WalletState.SignatureReceived

    LaunchedEffect(Unit) {
        val prefs = context.getSharedPreferences("NodePrefs", android.content.Context.MODE_PRIVATE)
        val cur = prefs.getString("currency", "usd") ?: "usd"

        // FIX: Instantly load the exact currency value from the new Map cache
        croPrice = globalCroPrices[cur] ?: 0.0

        coroutineScope.launch {
            try {
                val price = CryptoEngine.getCroPrice(cur)
                if (price > 0.0) {
                    croPrice = price
                    globalCroPrices[cur] = price
                }
            } catch (e: Exception) {}
        }

        coroutineScope.launch {
            validatorStats = CryptoEngine.getValidatorStats(
                validatorAddress = "crocncl1s078nr9kp4ulsxgnsasnr3k6zg5q9erps348eg",
                operatorWalletAddress = "cro1s078nr9kp4ulsxgnsasnr3k6zg5q9erpnukwm5" // <-- UPDATE THIS
            )
        }
    }

    LaunchedEffect(Unit) {
        wcEngine.walletEvents.collect { event: WalletEvent ->
            if (event is WalletEvent.TransactionRejected) {
                android.widget.Toast.makeText(context, "Wallet: ${event.message}", android.widget.Toast.LENGTH_LONG).show()
                isProcessing = false
            }
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {

        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(16.dp)
                .verticalScroll(scrollState),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {

            Row(
                modifier = Modifier.fillMaxWidth().padding(bottom = 16.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("About the Project", fontSize = 24.sp, fontWeight = FontWeight.Bold)

                if (isConnected) {
                    OutlinedButton(
                        onClick = { wcEngine.disconnect() },
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = Color.Red),
                        border = androidx.compose.foundation.BorderStroke(1.dp, Color.Red),
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp),
                        modifier = Modifier.height(32.dp)
                    ) {
                        Text("Disconnect", fontSize = 12.sp)
                    }
                } else {
                    Button(
                        onClick = {
                            wcEngine.connect { uri ->
                                // Force this entire block back onto the Main UI Thread
                                coroutineScope.launch(kotlinx.coroutines.Dispatchers.Main) {
                                    try {
                                        val intent = android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse(uri))
                                        intent.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                                        context.startActivity(intent)
                                    } catch (e: Exception) {
                                        android.widget.Toast.makeText(context, "No compatible wallet found.", android.widget.Toast.LENGTH_SHORT).show()
                                    }
                                }
                            }
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF1E88E5)),
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp),
                        modifier = Modifier.height(32.dp)
                    ) {
                        Text("Connect Wallet", fontSize = 12.sp)
                    }
                }
            }

            Card(
                modifier = Modifier.fillMaxWidth().padding(bottom = 16.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text("Hi! Santoshi here! 👋", fontSize = 18.sp, fontWeight = FontWeight.Bold, color = Color(0xFF1E88E5))
                    Spacer(modifier = Modifier.height(8.dp))

                    Text(
                        "Early CRO investor and official Crypto.com Ambassador since 2019. I built this App to help CROFam track and manage their staking rewards cleanly and natively, optimize yield through automatic restaking, and participate in the free no-loss lottery.",
                        fontSize = 14.sp, color = Color.LightGray
                    )

                    Spacer(modifier = Modifier.height(16.dp))
                    androidx.compose.material3.Divider()
                    Spacer(modifier = Modifier.height(8.dp))

                    Text("Connect with me:", fontSize = 14.sp, fontWeight = FontWeight.Bold)

                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                        TextButton(
                            onClick = { openLink(context, "mailto:santoshi.crypto@ethermail.io") },
                            contentPadding = PaddingValues(horizontal = 4.dp)
                        ) { Text("Email", fontSize = 13.sp) }

                        TextButton(
                            onClick = { openLink(context, "https://t.me/pmbrsantos") },
                            contentPadding = PaddingValues(horizontal = 4.dp)
                        ) { Text("Telegram", fontSize = 13.sp) }
                    }

                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                        TextButton(
                            onClick = { openLink(context, "https://x.com/SantoshiOG") },
                            contentPadding = PaddingValues(horizontal = 4.dp)
                        ) { Text("X (Twitter)", fontSize = 13.sp) }

                        TextButton(
                            onClick = { openLink(context, "https://instagram.com/pmbrsantos") },
                            contentPadding = PaddingValues(horizontal = 4.dp)
                        ) { Text("Instagram", fontSize = 13.sp) }

                        TextButton(
                            onClick = { openLink(context, "https://tiktok.com/@pmbrsantos") },
                            contentPadding = PaddingValues(horizontal = 4.dp)
                        ) { Text("TikTok", fontSize = 13.sp) }
                    }
                }
            }

            Card(
                modifier = Modifier.fillMaxWidth().padding(bottom = 24.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text("Why CROnquerorsNode?", fontSize = 18.sp, fontWeight = FontWeight.Bold)
                    Spacer(modifier = Modifier.height(8.dp))

                    BulletPoint("Long Time Supporter and Ambassador")
                    BulletPoint("25y+ Career in IT including Sysadmin")
                    BulletPoint("Active in Testnets")
                    BulletPoint("Skin in the game: 3M+ CRO self-delegated")
                    BulletPoint("Weekly Free Lottery access")
                    BulletPoint("Automatic Restaking with REStake.App")
                    BulletPoint("Reasonable 5% commission")
                }
            }

            Card(
                modifier = Modifier.fillMaxWidth().padding(bottom = 24.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text("Live CROnquerorsNode Stats", fontSize = 18.sp, fontWeight = FontWeight.Bold)
                    Spacer(modifier = Modifier.height(12.dp))

                    if (validatorStats != null) {

                        // VERIFIABLE FIX: Calculate the real APR dynamically using live node commission
                        val commission = validatorStats?.commissionRate ?: 0.0
                        val realApr = if (globalCachedApr > 0) globalCachedApr * (1.0 - (commission / 100.0)) else 0.0

                        Row(modifier = Modifier.fillMaxWidth()) {
                            Box(modifier = Modifier.weight(1f)) {
                                GridItem("TOTAL BONDED", "${String.format("%,.0f", validatorStats?.totalBondedCro)} CRO", "")
                            }
                            Box(modifier = Modifier.weight(1f)) {
                                GridItem("DELEGATORS", "${validatorStats?.delegatorCount}", "")
                            }
                        }
                        Spacer(modifier = Modifier.height(12.dp))
                        Row(modifier = Modifier.fillMaxWidth()) {
                            Box(modifier = Modifier.weight(1f)) {
                                GridItem("SELF BONDED", "${String.format("%,.0f", validatorStats?.selfBondedCro)} CRO", "Operator Stake")
                            }
                            Box(modifier = Modifier.weight(1f)) {
                                GridItem("MIN SELF-BOND", "${String.format("%,.0f", validatorStats?.minSelfDelegation)} CRO", "")
                            }
                        }
                        Spacer(modifier = Modifier.height(12.dp))
                        Row(modifier = Modifier.fillMaxWidth()) {
                            Box(modifier = Modifier.weight(1f)) {
                                GridItem("COMMISSION", "${String.format("%.0f", commission)}%", "")
                            }
                            Box(modifier = Modifier.weight(1f)) {
                                // Inject the newly calculated true APR here
                                GridItem("EST. REWARD APR", "${String.format("%.2f", realApr)}%", "After Commission")
                            }
                        }
                        Spacer(modifier = Modifier.height(12.dp))
                        Row(modifier = Modifier.fillMaxWidth()) {
                            Box(modifier = Modifier.weight(1f)) {
                                GridItem("NODE STATUS", validatorStats?.status ?: "UNKNOWN", if (validatorStats?.jailed == true) "Currently Jailed" else "Never Jailed")
                            }
                            Box(modifier = Modifier.weight(1f)) {
                                // Update these strings with your exact creation date and block height
                                GridItem("LIVE SINCE", "2021-03-25, 01:45:58 UTC", "Block #451")
                            }
                        }
                    } else {
                        LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                        Text("Fetching real-time stats from blockchain...", color = Color.Gray, fontSize = 12.sp, modifier = Modifier.padding(top = 8.dp))
                    }
                }
            }

            Text("Support the Project", fontSize = 20.sp, fontWeight = FontWeight.Bold)
            Text("If you find this tool useful, consider buying me a coffee!", fontSize = 12.sp, color = Color.Gray, modifier = Modifier.padding(bottom = 16.dp, top = 4.dp))

            if (isConnected) {
                val delegatorAddress = if (wcState is WalletState.Connected) {
                    (wcState as WalletState.Connected).address
                } else {
                    globalCachedAddress
                }

                if (croPrice > 0.0) {
                    DonationButton("☕ $1 Coffee", 1.0, croPrice, delegatorAddress, donationAddress, context, engine, wcEngine, coroutineScope) { isProcessing = it }
                    Spacer(modifier = Modifier.height(8.dp))
                    DonationButton("🍔 $5 Big Mac", 5.0, croPrice, delegatorAddress, donationAddress, context, engine, wcEngine, coroutineScope) { isProcessing = it }
                    Spacer(modifier = Modifier.height(8.dp))
                    DonationButton("🍟 $10 McMenu", 10.0, croPrice, delegatorAddress, donationAddress, context, engine, wcEngine, coroutineScope) { isProcessing = it }
                } else {
                    Text("Fetching live CRO price to calculate donations...", color = Color.Gray, fontSize = 12.sp)
                }
            } else {
                Box(
                    modifier = Modifier.fillMaxWidth().height(100.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text("Connect your wallet above to enable donations.", color = Color.Gray, fontSize = 14.sp)
                }
            }

            Spacer(modifier = Modifier.height(48.dp))
        }

        if (scrollState.maxValue > 0 && scrollState.value < scrollState.maxValue) {
            androidx.compose.material3.IconButton(
                onClick = {
                    coroutineScope.launch {
                        scrollState.animateScrollTo(scrollState.maxValue)
                    }
                },
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = 8.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.KeyboardArrowDown,
                    contentDescription = "Scroll Down",
                    tint = Color(0xFF1E88E5).copy(alpha = 0.8f),
                    modifier = Modifier.size(36.dp)
                )
            }
        }
    }
}

@Composable
fun BulletPoint(text: String) {
    Row(modifier = Modifier.padding(vertical = 4.dp)) {
        Text("• ", fontWeight = FontWeight.Bold, color = Color(0xFF1E88E5))
        Text(text, fontSize = 14.sp)
    }
}

fun openLink(context: android.content.Context, url: String) {
    try {
        val intent = android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse(url))
        context.startActivity(intent)
    } catch (e: Exception) {
        android.widget.Toast.makeText(context, "Unable to open link", android.widget.Toast.LENGTH_SHORT).show()
    }
}

@Composable
fun DonationButton(
    label: String,
    fiatTarget: Double,
    currentPrice: Double,
    fromAddress: String,
    toAddress: String,
    context: android.content.Context,
    engine: CryptoEngine,
    wcEngine: WalletConnectEngine,
    scope: kotlinx.coroutines.CoroutineScope,
    setProcessing: (Boolean) -> Unit
) {
    val croRequired = fiatTarget / currentPrice
    val baseCroRequired = (croRequired * 100_000_000).toLong()

    Button(
        onClick = {
            val msgSend = """
            [{
                "type": "cosmos-sdk/MsgSend",
                "value": {
                    "amount": [{
                        "amount": "$baseCroRequired",
                        "denom": "basecro"
                    }],
                    "from_address": "$fromAddress",
                    "to_address": "$toAddress"
                }
            }]
            """.trimIndent()

            executeTransaction(context, engine, wcEngine, fromAddress, msgSend, "Donation via Cronos (CRO) Staking Dashboard and Yield Optimizer", scope, setProcessing)
        },
        modifier = Modifier.fillMaxWidth(),
        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF1E88E5))
    ) {
        Text("$label (~${String.format("%.1f", croRequired)} CRO)")
    }
}