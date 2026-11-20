package com.example.smsapp.ui

import android.content.Context
import android.view.WindowManager
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.compose.foundation.layout.padding
import androidx.activity.compose.BackHandler
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Email
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.example.smsapp.data.SmsRepository
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
    var isPrivateSession by remember { mutableStateOf(false) }
    var selectedTab by rememberSaveable { mutableIntStateOf(0) }
    var authAddress by remember { mutableStateOf<String?>(null) }
    var pinEntry by remember { mutableStateOf("") }
    var pinError by remember { mutableStateOf<String?>(null) }

    // External intents (Dialer/Contacts SMS action) enter through this same lock check.
    LaunchedEffect(launch) {
        val external = launch?.address?.trim()?.takeIf { it.isNotEmpty() }
        if (external != null) {
            if (repo.isVaulted(external)) { currentAddress = null; isPrivateSession = false; authAddress = external } else { currentAddress = external; isPrivateSession = false; authAddress = null }
        }
    }

    DisposableEffect(lifecycleOwner, isPrivateSession) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_STOP && isPrivateSession) {
                currentAddress = null
                isPrivateSession = false
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    DisposableEffect(isPrivateSession) {
        val window = activity?.window
        if (isPrivateSession) window?.setFlags(WindowManager.LayoutParams.FLAG_SECURE, WindowManager.LayoutParams.FLAG_SECURE)
        else window?.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
        onDispose { if (isPrivateSession) window?.clearFlags(WindowManager.LayoutParams.FLAG_SECURE) }
    }

    // Publish the on-screen conversation only while the activity is visible, so backgrounded threads still notify.
    DisposableEffect(lifecycleOwner, currentAddress) {
        fun sync() { com.example.smsapp.notify.NotificationState.visibleAddress = if (lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) currentAddress else null }
        val observer = LifecycleEventObserver { _, _ -> sync() }
        lifecycleOwner.lifecycle.addObserver(observer)
        sync()
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer); com.example.smsapp.notify.NotificationState.visibleAddress = null }
    }

    fun openAddress(address: String) {
        scope.launch {
            if (repo.isVaulted(address)) authAddress = address
            else { currentAddress = address; isPrivateSession = false }
        }
    }

    fun openBiometric() {
        val host = activity ?: return
        val manager = BiometricManager.from(context)
        if (manager.canAuthenticate(BiometricManager.Authenticators.BIOMETRIC_STRONG) != BiometricManager.BIOMETRIC_SUCCESS) return
        val prompt = BiometricPrompt(host, ContextCompat.getMainExecutor(context), object : BiometricPrompt.AuthenticationCallback() {
            override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                authAddress?.let { currentAddress = it; isPrivateSession = true }
                authAddress = null; pinEntry = ""
            }
        })
        prompt.authenticate(BiometricPrompt.PromptInfo.Builder().setTitle("Unlock private conversation").setSubtitle("Authenticate to view protected messages").setNegativeButtonText("Use PIN").setAllowedAuthenticators(BiometricManager.Authenticators.BIOMETRIC_STRONG).build())
    }

    if (authAddress != null) {
        AlertDialog(onDismissRequest = { authAddress = null; pinEntry = "" }, title = { Text("Private conversation locked") }, text = {
            androidx.compose.foundation.layout.Column {
                OutlinedTextField(value = pinEntry, onValueChange = { pinEntry = it.filter(Char::isDigit).take(12); pinError = null }, label = { Text("Vault PIN") }, singleLine = true)
                pinError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                if (context.getSharedPreferences("sms_settings", android.content.Context.MODE_PRIVATE).getBoolean("vault_biometric", true)) TextButton(onClick = { openBiometric() }) { Text("Use fingerprint / device authentication") }
            }
        }, confirmButton = { TextButton(onClick = {
            val entered = pinEntry
            scope.launch {
                val valid = withContext(Dispatchers.IO) { security.verifyPin(entered) }
                if (valid) { currentAddress = authAddress; isPrivateSession = true; authAddress = null; pinEntry = "" }
                else { val wait = security.lockRemainingSeconds(); pinError = if (wait > 0) "Too many attempts. Try again in ${wait}s" else "Incorrect PIN" }
            }
        }) { Text("Unlock") } }, dismissButton = { TextButton(onClick = { authAddress = null; pinEntry = "" }) { Text("Cancel") } })
    }

    BackHandler(enabled = currentAddress != null) {
        currentAddress = null
        isPrivateSession = false
    }

    if (currentAddress != null) {
        ConversationScreen(repo, currentAddress!!, privateMode = isPrivateSession,
            onBack = { currentAddress = null; isPrivateSession = false },
            onBlock = { scope.launch { repo.block(currentAddress!!, true); currentAddress = null; isPrivateSession = false } },
            onUnvault = { scope.launch { repo.setVaulted(currentAddress!!, false); currentAddress = null; isPrivateSession = false } },
            onArchive = { scope.launch { repo.archive(currentAddress!!, true); currentAddress = null; isPrivateSession = false } },
            onDelete = { scope.launch { repo.deleteConversation(currentAddress!!); currentAddress = null; isPrivateSession = false } })
    } else {
        Scaffold(bottomBar = {
            NavigationBar {
                NavigationBarItem(selected = selectedTab == 0, onClick = { selectedTab = 0 }, icon = { Icon(Icons.Default.Email, "Messages") }, label = { Text("Messages") })
                NavigationBarItem(selected = selectedTab == 1, onClick = { selectedTab = 1 }, icon = { Icon(Icons.Default.Person, "Contacts") }, label = { Text("Contacts") })
                NavigationBarItem(selected = selectedTab == 2, onClick = { selectedTab = 2 }, icon = { Icon(Icons.Default.Palette, "Personalize") }, label = { Text("Personalize") })
                NavigationBarItem(selected = selectedTab == 3, onClick = { selectedTab = 3 }, icon = { Icon(Icons.Default.Settings, "Settings") }, label = { Text("Settings") })
            }
        }) { padding ->
            when (selectedTab) {
                0 -> ConversationListScreen(repo, onOpen = ::openAddress, onLongPress = { address ->
                    if (!security.hasPin()) android.widget.Toast.makeText(context, "Set a vault PIN in Settings first", android.widget.Toast.LENGTH_LONG).show()
                    else scope.launch { runCatching { repo.setVaulted(address, true) }.onFailure { android.widget.Toast.makeText(context, "Could not secure conversation", android.widget.Toast.LENGTH_LONG).show() } }
                }, modifier = Modifier.padding(padding))
                1 -> ContactsScreen(onMessage = ::openAddress, modifier = Modifier.padding(padding))
                2 -> PersonalizeScreen(Modifier.padding(padding))
                else -> SettingsScreen(repo = repo, onOpenConversation = ::openAddress, onOpenPersonalize = { selectedTab = 2 }, modifier = Modifier.padding(padding))
            }
        }
    }
}
