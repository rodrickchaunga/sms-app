package com.example.smsapp.ui

import android.view.WindowManager
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.compose.foundation.layout.padding
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Email
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.example.smsapp.data.SmsRepository
import com.example.smsapp.notify.NotificationHelper
import com.example.smsapp.notify.NotificationState
import com.example.smsapp.security.VaultSecurity
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Composable
fun SmsNavHost(repo: SmsRepository, launch: LaunchRequest?) {
    val context = LocalContext.current
    val activity = context as? FragmentActivity
    val lifecycleOwner = LocalLifecycleOwner.current
    val scope = rememberCoroutineScope()
    val security = remember { VaultSecurity(context) }
    var currentAddress by remember { mutableStateOf<String?>(null) }
    // True while the open chat belongs to the private space.
    var isPrivateSession by remember { mutableStateOf(false) }
    var inPrivateSpace by remember { mutableStateOf(false) }
    var showNewConversation by remember { mutableStateOf(false) }
    var showUnlock by remember { mutableStateOf(false) }
    var showPinSetup by remember { mutableStateOf(false) }
    var selectedTab by rememberSaveable { mutableIntStateOf(0) }
    var pinEntry by remember { mutableStateOf("") }
    var pinError by remember { mutableStateOf<String?>(null) }

    fun closeChat() { currentAddress = null; isPrivateSession = false }

    /**
     * The only way to a private conversation is through the private space (long-press the new-conversation button).
     * Notification taps, sms: intents, contacts and typed numbers all end up here, and a private number is simply not
     * opened outside the space: nothing is shown and nothing says why.
     */
    fun openAddress(address: String) {
        scope.launch {
            val resolved = repo.resolveAddress(address)
            if (repo.isVaulted(resolved)) return@launch
            showNewConversation = false
            currentAddress = resolved
            isPrivateSession = false
        }
    }

    LaunchedEffect(launch) {
        val external = launch?.address?.trim()?.takeIf { it.isNotEmpty() }
        if (external != null) openAddress(external)
    }

    fun enterPrivateSpace() {
        showUnlock = false; showPinSetup = false; pinEntry = ""; pinError = null
        NotificationHelper.cancelPrivate(context)
        inPrivateSpace = true
    }

    fun requestPrivateSpace() {
        if (!security.hasPin()) showPinSetup = true
        else { pinEntry = ""; pinError = null; showUnlock = true }
    }

    // Leaving the app locks the private space again.
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_STOP) {
                if (isPrivateSession) closeChat()
                inPrivateSpace = false
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    val privateOnScreen = isPrivateSession || inPrivateSpace
    DisposableEffect(privateOnScreen) {
        val window = activity?.window
        if (privateOnScreen) window?.setFlags(WindowManager.LayoutParams.FLAG_SECURE, WindowManager.LayoutParams.FLAG_SECURE)
        else window?.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
        onDispose { if (privateOnScreen) window?.clearFlags(WindowManager.LayoutParams.FLAG_SECURE) }
    }

    // Publish the on-screen conversation only while the activity is visible, so backgrounded threads still notify.
    DisposableEffect(lifecycleOwner, currentAddress) {
        fun sync() { NotificationState.visibleAddress = if (lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) currentAddress else null }
        val observer = LifecycleEventObserver { _, _ -> sync() }
        lifecycleOwner.lifecycle.addObserver(observer)
        sync()
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer); NotificationState.visibleAddress = null }
    }
    DisposableEffect(lifecycleOwner, inPrivateSpace, currentAddress) {
        fun sync() { NotificationState.privateSpaceVisible = inPrivateSpace && currentAddress == null && lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED) }
        val observer = LifecycleEventObserver { _, _ -> sync() }
        lifecycleOwner.lifecycle.addObserver(observer)
        sync()
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer); NotificationState.privateSpaceVisible = false }
    }

    fun openBiometric() {
        val host = activity ?: return
        val manager = BiometricManager.from(context)
        if (manager.canAuthenticate(BiometricManager.Authenticators.BIOMETRIC_STRONG) != BiometricManager.BIOMETRIC_SUCCESS) return
        val prompt = BiometricPrompt(host, ContextCompat.getMainExecutor(context), object : BiometricPrompt.AuthenticationCallback() {
            override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) { enterPrivateSpace() }
        })
        prompt.authenticate(BiometricPrompt.PromptInfo.Builder().setTitle("Unlock").setSubtitle("Authenticate to continue").setNegativeButtonText("Use PIN").setAllowedAuthenticators(BiometricManager.Authenticators.BIOMETRIC_STRONG).build())
    }

    if (showUnlock) {
        AlertDialog(onDismissRequest = { showUnlock = false; pinEntry = "" }, title = { Text("Enter PIN") }, text = {
            androidx.compose.foundation.layout.Column {
                OutlinedTextField(
                    value = pinEntry, onValueChange = { pinEntry = it.filter(Char::isDigit).take(12); pinError = null }, label = { Text("PIN") }, singleLine = true,
                    visualTransformation = PasswordVisualTransformation(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword)
                )
                pinError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                if (context.getSharedPreferences("sms_settings", android.content.Context.MODE_PRIVATE).getBoolean("vault_biometric", true)) TextButton(onClick = { openBiometric() }) { Text("Use fingerprint / device authentication") }
            }
        }, confirmButton = { TextButton(onClick = {
            val entered = pinEntry
            scope.launch {
                val valid = withContext(Dispatchers.IO) { security.verifyPin(entered) }
                if (valid) enterPrivateSpace()
                else { val wait = security.lockRemainingSeconds(); pinError = if (wait > 0) "Too many attempts. Try again in ${wait}s" else "Incorrect PIN" }
            }
        }) { Text("Unlock") } }, dismissButton = { TextButton(onClick = { showUnlock = false; pinEntry = "" }) { Text("Cancel") } })
    }
    if (showPinSetup) PinDialog(security, "Set a PIN", onDismiss = { showPinSetup = false }, onSaved = { enterPrivateSpace() })

    BackHandler(enabled = currentAddress != null) { closeChat() }

    val openChat = currentAddress
    if (openChat != null) {
        ConversationScreen(repo, openChat, privateMode = isPrivateSession,
            onBack = ::closeChat,
            onBlock = { scope.launch { repo.block(openChat, true); closeChat() } },
            onUnvault = { scope.launch { repo.setVaulted(openChat, false); closeChat() } },
            onArchive = { scope.launch { repo.archive(openChat, true); closeChat() } },
            onDelete = { scope.launch { repo.deleteConversation(openChat); closeChat() } })
    } else if (showNewConversation) {
        NewConversationScreen(onBack = { showNewConversation = false }, onChoose = ::openAddress)
    } else if (inPrivateSpace) {
        PrivateSpaceScreen(repo, security,
            onOpen = { address -> currentAddress = address; isPrivateSession = true },
            onExit = { inPrivateSpace = false })
    } else {
        Scaffold(bottomBar = {
            NavigationBar {
                NavigationBarItem(selected = selectedTab == 0, onClick = { selectedTab = 0 }, icon = { Icon(Icons.Default.Email, "Messages") }, label = { Text("Messages") })
                NavigationBarItem(selected = selectedTab == 1, onClick = { selectedTab = 1 }, icon = { Icon(Icons.Default.Palette, "Personalize") }, label = { Text("Personalize") })
                NavigationBarItem(selected = selectedTab == 2, onClick = { selectedTab = 2 }, icon = { Icon(Icons.Default.Settings, "Settings") }, label = { Text("Settings") })
            }
        }) { padding ->
            when (selectedTab) {
                0 -> ConversationListScreen(repo, onOpen = ::openAddress, onNewConversation = { showNewConversation = true }, onPrivateSpace = ::requestPrivateSpace, modifier = Modifier.padding(padding))
                1 -> PersonalizeScreen(Modifier.padding(padding))
                else -> SettingsScreen(repo = repo, onOpenConversation = ::openAddress, onOpenPersonalize = { selectedTab = 1 }, modifier = Modifier.padding(padding))
            }
        }
    }
}
