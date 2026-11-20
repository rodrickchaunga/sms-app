package com.example.smsapp

import android.Manifest
import android.app.role.RoleManager
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.fragment.app.FragmentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.material3.MaterialTheme
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.example.smsapp.appearance.AppearanceSettings
import com.example.smsapp.appearance.SmsTheme
import com.example.smsapp.notify.NotificationHelper
import androidx.compose.runtime.collectAsState
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import com.example.smsapp.ui.LaunchRequest
import com.example.smsapp.ui.PermissionGate
import com.example.smsapp.ui.SmsNavHost
import kotlinx.coroutines.launch

class MainActivity : FragmentActivity() {
    private val required = arrayOf(Manifest.permission.SEND_SMS, Manifest.permission.RECEIVE_SMS, Manifest.permission.READ_SMS, Manifest.permission.READ_CONTACTS)
    // Notifications are requested together with the core permissions but are not required to use the app.
    private val requested: Array<String> = buildList {
        addAll(required)
        add(Manifest.permission.READ_PHONE_STATE) // lets the app list SIMs; sending works without it
        if (Build.VERSION.SDK_INT >= 33) add(Manifest.permission.POST_NOTIFICATIONS)
    }.toTypedArray()
    private var launchRequest by mutableStateOf<LaunchRequest?>(null)
    private var launchNonce = 0L
    private var initialAppearance = AppearanceSettings()
    private val permissionLauncher = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { render() }
    private val roleLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { render() }
    private fun hasPermissions() = required.all { ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED }
    private fun isDefaultSmsApp(): Boolean = if (Build.VERSION.SDK_INT >= 29) getSystemService(RoleManager::class.java).isRoleHeld(RoleManager.ROLE_SMS) else android.provider.Telephony.Sms.getDefaultSmsPackage(this) == packageName

    /**
     * Every entry route (sms:/smsto: intents, notification taps) ends up here and then in SmsNavHost,
     * which checks the vault before showing anything. Private notifications carry no address at all.
     */
    private fun handleLaunchIntent(intent: Intent?) {
        val address = intent?.getStringExtra(NotificationHelper.EXTRA_OPEN_ADDRESS)
            ?: intent?.data?.schemeSpecificPart?.substringBefore('?')
        if (!address.isNullOrBlank()) launchRequest = LaunchRequest(address, ++launchNonce)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // After rotation / process recreation Android redelivers the original intent; do not replay it.
        if (savedInstanceState == null) handleLaunchIntent(intent)
        // One tiny DataStore read so the first frame already uses the saved theme (no light-to-dark flash).
        initialAppearance = runCatching { runBlocking { (application as SmsApp).appearance.flow.first() } }.getOrDefault(AppearanceSettings())
        render()
        window.decorView.post {
            if (!isDefaultSmsApp()) {
                if (Build.VERSION.SDK_INT >= 29) {
                    val roles = getSystemService(RoleManager::class.java)
                    if (roles.isRoleAvailable(RoleManager.ROLE_SMS)) roleLauncher.launch(roles.createRequestRoleIntent(RoleManager.ROLE_SMS))
                } else {
                    val changeDefault = Intent(android.provider.Telephony.Sms.Intents.ACTION_CHANGE_DEFAULT)
                        .putExtra(android.provider.Telephony.Sms.Intents.EXTRA_PACKAGE_NAME, packageName)
                    roleLauncher.launch(changeDefault)
                }
            }
        }
    }
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleLaunchIntent(intent)
    }

    private fun render() {
        val app = application as SmsApp
        setContent {
            val appearance by app.appearance.flow.collectAsState(initial = initialAppearance)
            SmsTheme(appearance) {
                if (!hasPermissions()) PermissionGate(onRequest = { permissionLauncher.launch(requested) }, onGranted = {})
                else SmsNavHost(repo = app.repo, launch = launchRequest)
            }
        }
        if (hasPermissions()) lifecycleScope.launch { runCatching { app.repo.syncProviderMessages() } }
    }
}
