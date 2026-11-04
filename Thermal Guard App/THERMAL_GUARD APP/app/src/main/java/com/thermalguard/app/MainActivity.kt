package com.thermalguard.app

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.View
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.fragment.app.Fragment
import com.google.android.gms.auth.api.signin.*
import com.google.android.gms.common.api.ApiException
import com.google.android.gms.auth.api.signin.GoogleSignInStatusCodes
import com.google.firebase.FirebaseApp
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.GoogleAuthProvider
import com.google.firebase.database.ValueEventListener
import com.thermalguard.app.data.ThermalRepository
import com.thermalguard.app.databinding.ActivityMainBinding
import com.thermalguard.app.model.AppUser
import com.thermalguard.app.model.ControllerHealth
import com.thermalguard.app.model.LiveSystem
import com.thermalguard.app.model.TemperatureAlertEvaluator
import com.thermalguard.app.ui.*

class MainActivity : AppCompatActivity(), LoginFragment.Callback, PendingFragment.Callback {
    private lateinit var binding: ActivityMainBinding
    private val auth by lazy { FirebaseAuth.getInstance() }
    private val repo by lazy { ThermalRepository() }
    private lateinit var googleClient: GoogleSignInClient
    private var firebaseReady: Boolean = false
    private var startupFirebaseError: String? = null
    var profile: AppUser? = null
        private set
    private var profileListener: ValueEventListener? = null
    private var alertLiveListener: ValueEventListener? = null
    private var currentNavItemId: Int = View.NO_ID

    // Lost-contact detection. A Raspberry Pi that stopped (power cut, crash, fire)
    // cannot push anything, so the app watches its heartbeat while monitoring.
    // Timing uses elapsedRealtime, so phone clock changes cannot trigger it.
    private val contactHandler = Handler(Looper.getMainLooper())
    private var latestMonitorSystem: LiveSystem? = null
    private var offlineSinceElapsed = 0L
    private var contactLostShown = false
    private val contactWatchdog = object : Runnable {
        override fun run() {
            checkControllerContact()
            contactHandler.postDelayed(this, CONTACT_CHECK_MS)
        }
    }

    // Firebase may deliver the cached user profile and then the server profile again
    // during the first seconds after launch.  Routing on every profile callback used to
    // reopen Dashboard while the user was entering Settings, which looked like Settings
    // had auto-closed.  Route only once for an active signed-in session.
    private var activeProfileRouteCompleted = false
    private var pendingDestination: String? = null
    private var navigationSerial: Long = 0L
    private var deferredFragment: Fragment? = null
    private var deferredNavVisible: Boolean = true
    private var deferredNavigationSerial: Long = 0L

    private val googleLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val data = result.data
        if (data == null) {
            toast(getString(R.string.google_sign_in_cancelled))
            return@registerForActivityResult
        }

        try {
            val account = GoogleSignIn.getSignedInAccountFromIntent(data).getResult(ApiException::class.java)
            val idToken = account.idToken
            if (idToken.isNullOrBlank()) {
                toast("Google Sign-In did not return an ID token. Check Web OAuth client configuration.")
                return@registerForActivityResult
            }

            val credential = GoogleAuthProvider.getCredential(idToken, null)
            auth.signInWithCredential(credential)
                .addOnSuccessListener { routeAfterAuth() }
                .addOnFailureListener { error ->
                    toast("Firebase Google authentication failed: ${error.localizedMessage ?: "unknown error"}")
                }
        } catch (e: ApiException) {
            val message = when (e.statusCode) {
                GoogleSignInStatusCodes.SIGN_IN_CANCELLED -> getString(R.string.google_sign_in_cancelled)
                GoogleSignInStatusCodes.SIGN_IN_CURRENTLY_IN_PROGRESS -> "Google Sign-In is already in progress"
                10 -> "Google Sign-In configuration error (10). Add this app's SHA-1/SHA-256 in Firebase, then download a fresh google-services.json."
                else -> "${getString(R.string.google_sign_in_failed)} (${e.statusCode}: ${GoogleSignInStatusCodes.getStatusCodeString(e.statusCode)})"
            }
            toast(message)
        } catch (e: Exception) {
            toast("${getString(R.string.google_sign_in_failed)}: ${e.localizedMessage ?: "unknown error"}")
        }
    }

    private val notificationPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) {}

    override fun onCreate(savedInstanceState: Bundle?) {
        // Apply the user's saved Light/Dark preference before the Activity is created so
        // every screen, including Settings, uses the correct high-contrast palette.
        val darkMode = getSharedPreferences("thermal_guard_ui", MODE_PRIVATE)
            .getBoolean("dark_mode", true)
        AppCompatDelegate.setDefaultNightMode(
            if (darkMode) AppCompatDelegate.MODE_NIGHT_YES
            else AppCompatDelegate.MODE_NIGHT_NO
        )

        // Replace the cold-start splash theme before inflating the real UI.
        setTheme(R.style.Theme_ThermalGuard)
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        currentNavItemId = savedInstanceState?.getInt(STATE_NAV_ITEM, View.NO_ID) ?: View.NO_ID
        pendingDestination = savedInstanceState?.getString(STATE_PENDING_DESTINATION)
        if (savedInstanceState != null && currentNavItemId != View.NO_ID) {
            // FragmentManager already restores the visible Fragment. Preserve it instead of
            // immediately reopening Dashboard when Firebase returns the first profile snapshot.
            activeProfileRouteCompleted = true
        }

        firebaseReady = try {
            FirebaseApp.getApps(this).firstOrNull() ?: FirebaseApp.initializeApp(this)
            FirebaseApp.getApps(this).isNotEmpty()
        } catch (e: Exception) {
            startupFirebaseError = e.localizedMessage ?: e.javaClass.simpleName
            false
        }

        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(0, bars.top, 0, bars.bottom)
            insets
        }

        googleClient = try {
            GoogleSignIn.getClient(
                this,
                GoogleSignInOptions.Builder(GoogleSignInOptions.DEFAULT_SIGN_IN)
                    .requestIdToken(getString(R.string.default_web_client_id))
                    .requestEmail()
                    .requestProfile()
                    .build()
            )
        } catch (e: Exception) {
            Toast.makeText(this, "Google Sign-In configuration error: ${e.message ?: "unknown"}", Toast.LENGTH_LONG).show()
            GoogleSignIn.getClient(this, GoogleSignInOptions.DEFAULT_SIGN_IN)
        }

        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)

        // Use normal clickable views instead of BottomNavigationView menu rendering.
        // This prevents invisible icons on devices/themes that incorrectly tint menu drawables.
        setupCustomNavigation()
        if (savedInstanceState != null && currentNavItemId == View.NO_ID) {
            currentNavItemId = navIdForFragment(supportFragmentManager.findFragmentById(R.id.content))
            if (currentNavItemId != View.NO_ID) activeProfileRouteCompleted = true
        }

        runCatching { routeAfterAuth(intent) }
            .onFailure {
                hideMainNavigation()
                show(LoginFragment(), false)
                toast("Startup recovered: ${it.message ?: "initialization error"}")
            }
    }

    override fun onStart() {
        super.onStart()
        // Opening Thermal Guard means the user has acknowledged the audible app alarm.
        // Keep monitoring active, but stop the current WARNING/CRITICAL sound.
        TemperatureAlertService.silenceIfActive(applicationContext)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        TemperatureAlertService.silenceIfActive(applicationContext)
        intent.getStringExtra("destination")
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
            ?.let { pendingDestination = it }

        if (firebaseReady && auth.currentUser != null && profile?.active == true) {
            val destination = pendingDestination
            pendingDestination = null
            activeProfileRouteCompleted = true
            openDestination(destination)
        }
    }

    fun routeAfterAuth(sourceIntent: Intent? = null) {
        sourceIntent?.getStringExtra("destination")
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
            ?.let { pendingDestination = it }
        if (!firebaseReady) {
            profile = null
            activeProfileRouteCompleted = false
            hideMainNavigation()
            show(LoginFragment(), false)
            toast("Firebase is not ready${startupFirebaseError?.let { ": $it" } ?: ""}")
            return
        }

        if (auth.currentUser == null) {
            profile = null
            activeProfileRouteCompleted = false
            setNotificationsEnabled(false)
            hideMainNavigation()
            show(LoginFragment(), false)
            return
        }

        profileListener?.let(repo::removeCurrentUserObserver)
        profileListener = repo.observeCurrentUser({ p ->
            profile = p
            val role = p?.role?.trim()?.lowercase().orEmpty()

            when {
                p == null -> {
                    activeProfileRouteCompleted = false
                    setNotificationsEnabled(false)
                    repo.submitPendingStaff()
                    hideMainNavigation()
                    show(PendingFragment.newInstance(false), false)
                }
                !p.active -> {
                    activeProfileRouteCompleted = false
                    setNotificationsEnabled(false)
                    hideMainNavigation()
                    show(PendingFragment.newInstance(true), false)
                }
                role == "admin" || role == "staff" -> {
                    setNotificationsEnabled(true)
                    configureNavigationForRole(role)

                    // IMPORTANT: do not reopen Dashboard on the second profile callback.
                    // Firebase commonly emits cached data followed by fresh server data on
                    // a cold start.  If the user already tapped Settings, preserve it.
                    if (!activeProfileRouteCompleted || currentNavItemId == View.NO_ID) {
                        val destination = pendingDestination
                        pendingDestination = null
                        activeProfileRouteCompleted = true
                        openDestination(destination)
                    } else {
                        updateSelectedNavigation(currentNavItemId)
                    }
                }
                else -> {
                    activeProfileRouteCompleted = false
                    setNotificationsEnabled(false)
                    repo.submitPendingStaff()
                    hideMainNavigation()
                    show(PendingFragment.newInstance(false), false)
                }
            }
        }, { error ->
            // A transient Firebase reconnect must not tear down the screen the user is using.
            // Only show the logged-out shell when there is no known active profile yet.
            if (profile?.active != true) {
                hideMainNavigation()
                show(LoginFragment(), false)
            }
            toast(error)
        })
    }

    private fun setupCustomNavigation() {
        val destinations = listOf(
            R.id.navDashboard to binding.navDashboard,
            R.id.navAlerts to binding.navAlerts,
            R.id.navDiagnostics to binding.navDiagnostics,
            R.id.navStaff to binding.navStaff,
            R.id.navSettings to binding.navSettings
        )

        destinations.forEach { (itemId, itemView) ->
            itemView.setOnClickListener {
                val reselected = currentNavItemId == itemId
                navigateTo(itemId, force = reselected)
            }
        }
        updateSelectedNavigation(View.NO_ID)
    }

    private fun configureNavigationForRole(role: String) {
        val normalizedRole = role.trim().lowercase()
        val fourthTitle = if (normalizedRole == "admin") "Staff" else "Access"

        binding.navStaffLabel.text = fourthTitle
        binding.navStaff.contentDescription = fourthTitle
        binding.navStaffIcon.contentDescription = fourthTitle

        listOf(
            binding.navDashboard,
            binding.navAlerts,
            binding.navDiagnostics,
            binding.navStaff,
            binding.navSettings
        ).forEach { item ->
            item.visibility = View.VISIBLE
            item.isEnabled = true
            item.isClickable = true
            item.alpha = 1f
        }

        binding.bottomNav.visibility = View.VISIBLE
        binding.bottomNav.alpha = 1f
        binding.bottomNav.bringToFront()
        binding.bottomNav.requestLayout()
    }

    private fun updateSelectedNavigation(selectedItemId: Int) {
        val destinations = listOf(
            Triple(R.id.navDashboard, binding.navDashboard, binding.navDashboardIcon) to binding.navDashboardLabel,
            Triple(R.id.navAlerts, binding.navAlerts, binding.navAlertsIcon) to binding.navAlertsLabel,
            Triple(R.id.navDiagnostics, binding.navDiagnostics, binding.navDiagnosticsIcon) to binding.navDiagnosticsLabel,
            Triple(R.id.navStaff, binding.navStaff, binding.navStaffIcon) to binding.navStaffLabel,
            Triple(R.id.navSettings, binding.navSettings, binding.navSettingsIcon) to binding.navSettingsLabel
        )

        destinations.forEach { entry ->
            val (itemId, itemView, iconView) = entry.first
            val labelView = entry.second
            val selected = itemId == selectedItemId
            itemView.isSelected = selected
            itemView.isActivated = selected
            iconView.isSelected = selected
            iconView.isActivated = selected
            labelView.isSelected = selected
            labelView.isActivated = selected
        }
    }

    private fun hideMainNavigation() {
        currentNavItemId = View.NO_ID
        updateSelectedNavigation(View.NO_ID)
        binding.bottomNav.visibility = View.GONE
    }

    private fun navigateTo(itemId: Int, force: Boolean = false): Boolean {
        val p = profile ?: return false
        if (!p.active) return false

        val role = p.role.trim().lowercase()
        if (role != "admin" && role != "staff") return false

        if (!force && currentNavItemId == itemId) {
            updateSelectedNavigation(itemId)
            return true
        }

        val fragment: Fragment = when (itemId) {
            R.id.navDashboard -> DashboardFragment()
            R.id.navAlerts -> AlertsFragment()
            R.id.navDiagnostics -> DiagnosticsFragment()
            R.id.navStaff -> if (role == "admin") StaffFragment() else AccessFragment()
            R.id.navSettings -> SettingsFragment()
            else -> return false
        }

        currentNavItemId = itemId
        updateSelectedNavigation(itemId)
        show(fragment, true)
        return true
    }

    private fun selectNavigation(itemId: Int) {
        binding.bottomNav.visibility = View.VISIBLE
        val reselected = currentNavItemId == itemId
        navigateTo(itemId, force = reselected)
    }

    private fun openDestination(destination: String?) {
        val role = profile?.role?.trim()?.lowercase().orEmpty()
        val itemId = when (destination?.trim()?.lowercase()) {
            "alerts" -> R.id.navAlerts
            "diagnostics" -> R.id.navDiagnostics
            "staff", "access" -> R.id.navStaff
            "settings" -> R.id.navSettings
            "dashboard", "home" -> R.id.navDashboard
            else -> R.id.navDashboard
        }

        // Staff can open the same fourth icon safely; it maps to Access instead of admin management.
        if (itemId == R.id.navStaff && role != "admin" && role != "staff") {
            selectNavigation(R.id.navDashboard)
        } else {
            selectNavigation(itemId)
        }
    }

    private fun show(fragment: Fragment, navVisible: Boolean = true) {
        if (isFinishing || isDestroyed) return

        val requestSerial = ++navigationSerial
        binding.bottomNav.visibility = if (navVisible) View.VISIBLE else View.GONE
        commitFragmentWhenSafe(fragment, navVisible, requestSerial)
    }

    private fun commitFragmentWhenSafe(fragment: Fragment, navVisible: Boolean, requestSerial: Long) {
        if (requestSerial != navigationSerial || isFinishing || isDestroyed) return
        val fm = supportFragmentManager
        if (fm.isStateSaved) {
            // Android is between onSaveInstanceState and resume. Keep only the newest request
            // and commit it from onPostResume; older taps can never replace the latest screen.
            deferredFragment = fragment
            deferredNavVisible = navVisible
            deferredNavigationSerial = requestSerial
            return
        }

        deferredFragment = null
        deferredNavigationSerial = 0L
        binding.bottomNav.visibility = if (navVisible) View.VISIBLE else View.GONE
        runCatching {
            fm.beginTransaction()
                .setReorderingAllowed(true)
                .replace(R.id.content, fragment, fragment.javaClass.name)
                .commit()
        }.onFailure { error ->
            if (!isFinishing && !isDestroyed && requestSerial == navigationSerial) {
                runCatching {
                    fm.beginTransaction()
                        .setReorderingAllowed(true)
                        .replace(R.id.content, fragment, fragment.javaClass.name)
                        .commitAllowingStateLoss()
                }.onFailure {
                    toast("Screen could not open: ${error.localizedMessage ?: "navigation error"}")
                }
            }
        }
    }

    override fun onPostResume() {
        super.onPostResume()
        val fragment = deferredFragment ?: return
        val serial = deferredNavigationSerial
        val navVisible = deferredNavVisible
        deferredFragment = null
        deferredNavigationSerial = 0L
        commitFragmentWhenSafe(fragment, navVisible, serial)
    }

    private fun navIdForFragment(fragment: Fragment?): Int = when (fragment) {
        is DashboardFragment -> R.id.navDashboard
        is AlertsFragment -> R.id.navAlerts
        is DiagnosticsFragment -> R.id.navDiagnostics
        is StaffFragment, is AccessFragment -> R.id.navStaff
        is SettingsFragment -> R.id.navSettings
        else -> View.NO_ID
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putInt(STATE_NAV_ITEM, currentNavItemId)
        pendingDestination?.let { outState.putString(STATE_PENDING_DESTINATION, it) }
        super.onSaveInstanceState(outState)
    }

    override fun onEmailAuthenticated() {
        routeAfterAuth()
    }

    override fun signInWithGoogle() {
        if (!firebaseReady) {
            toast("Firebase configuration is not ready. Check app/google-services.json.")
            return
        }

        runCatching {
            googleClient.signOut().addOnCompleteListener {
                runCatching { googleLauncher.launch(googleClient.signInIntent) }
                    .onFailure { toast("Unable to open Google Sign-In: ${it.localizedMessage ?: "unknown error"}") }
            }
        }.onFailure {
            toast("Google Sign-In unavailable: ${it.localizedMessage ?: "unknown error"}")
        }
    }

    override fun refreshAccount() = routeAfterAuth()

    override fun signOut() {
        activeProfileRouteCompleted = false
        pendingDestination = null
        profileListener?.let(repo::removeCurrentUserObserver)
        profileListener = null
        profile = null
        hideMainNavigation()
        // Remove this phone's push token while still signed in (only the owner may),
        // then sign out.
        setNotificationsEnabled(false) {
            if (firebaseReady) runCatching { auth.signOut() }
            runCatching {
                googleClient.signOut().addOnCompleteListener { routeAfterAuth() }
            }.onFailure { routeAfterAuth() }
        }
    }

    fun openAlerts() = selectNavigation(R.id.navAlerts)

    fun openSettings() = selectNavigation(R.id.navSettings)

    private fun setNotificationsEnabled(enabled: Boolean, onDone: () -> Unit = {}) {
        if (!firebaseReady) {
            if (!enabled) stopTemperatureAlertMonitor(clearAlarm = true)
            onDone()
            return
        }
        val userPref = getSharedPreferences("thermal_guard_ui", 0).getBoolean("notifications", true)
        val effective = enabled && userPref
        // Pushes now go to this phone's registered token (see PushRegistration),
        // so removing someone's access stops their alarms at the Raspberry Pi.
        if (effective) {
            PushRegistration.enable(applicationContext, repo)
            onDone()
        } else {
            PushRegistration.disable(applicationContext, repo, onDone)
        }

        if (effective) {
            startTemperatureAlertMonitor()
        } else {
            stopTemperatureAlertMonitor(clearAlarm = true)
        }
    }

    /** Called by Settings so the live alarm monitor follows the user's switch immediately. */
    fun updateNotificationPreference(enabled: Boolean) {
        getSharedPreferences("thermal_guard_ui", 0)
            .edit()
            .putBoolean("notifications", enabled)
            .apply()
        val activeRole = profile?.role?.trim()?.lowercase().orEmpty()
        val activeAccount = profile?.active == true && activeRole in setOf("admin", "staff")
        setNotificationsEnabled(activeAccount)
    }

    /**
     * Monitors fresh Firebase readings on every signed-in screen, not only Dashboard.
     * The hottest valid online sensor is compared with the exact warning/critical
     * thresholds published by the Raspberry Pi.
     */
    private fun startTemperatureAlertMonitor() {
        if (alertLiveListener != null || auth.currentUser == null || profile?.active != true) return
        alertLiveListener = repo.observeLive({ system, zones ->
            latestMonitorSystem = system
            val state = TemperatureAlertEvaluator.evaluate(system, zones)
            // A stale snapshot (Pi heartbeat lost) never starts or clears an alarm here;
            // the alarm service keeps an active alarm and marks it LOST CONTACT.
            if (!state.controllerLost) TemperatureAlertService.apply(applicationContext, state)
            checkControllerContact()
        }, {
            // Do not stop an active alarm because of a temporary Firebase disconnect.
        })
        contactHandler.removeCallbacks(contactWatchdog)
        contactHandler.postDelayed(contactWatchdog, CONTACT_CHECK_MS)
    }

    private fun checkControllerContact() {
        val system = latestMonitorSystem ?: return
        if (ControllerHealth.isOnline(system)) {
            offlineSinceElapsed = 0L
            if (contactLostShown) {
                ContactLostNotifier.cancel(applicationContext)
                contactLostShown = false
            }
            return
        }
        val now = SystemClock.elapsedRealtime()
        if (offlineSinceElapsed == 0L) offlineSinceElapsed = now
        val offlineMs = now - offlineSinceElapsed
        if (!contactLostShown && offlineMs >= CONTACT_LOST_AFTER_MS &&
            !TemperatureAlertService.isAlarmActive(applicationContext)
        ) {
            ContactLostNotifier.show(applicationContext, offlineMs / 1000L)
            contactLostShown = true
        }
    }

    private fun stopTemperatureAlertMonitor(clearAlarm: Boolean) {
        alertLiveListener?.let(repo::removeLiveObserver)
        alertLiveListener = null
        contactHandler.removeCallbacks(contactWatchdog)
        latestMonitorSystem = null
        offlineSinceElapsed = 0L
        if (clearAlarm) {
            TemperatureAlertService.clear(applicationContext)
            ContactLostNotifier.cancel(applicationContext)
            contactLostShown = false
        }
    }

    fun toast(message: String) {
        if (isFinishing || isDestroyed) return
        Toast.makeText(applicationContext, message, Toast.LENGTH_SHORT).show()
    }

    override fun onDestroy() {
        profileListener?.let(repo::removeCurrentUserObserver)
        profileListener = null
        // Remove the Activity observer only. An already-running foreground alarm
        // service keeps monitoring Firebase until the temperature returns to normal.
        stopTemperatureAlertMonitor(clearAlarm = false)
        super.onDestroy()
    }

    companion object {
        private const val STATE_NAV_ITEM = "thermal_guard.nav_item"
        private const val STATE_PENDING_DESTINATION = "thermal_guard.pending_destination"
        private const val CONTACT_CHECK_MS = 5_000L
        /** Ride out short hotspot/Wi-Fi blips before warning that monitoring is lost. */
        private const val CONTACT_LOST_AFTER_MS = 60_000L
    }
}
