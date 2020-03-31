package network.mysterium

import android.app.Activity
import android.app.NotificationManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.net.VpnService
import android.os.Bundle
import android.os.IBinder
import android.util.Log
import android.view.View
import android.widget.LinearLayout
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.GravityCompat
import androidx.drawerlayout.widget.DrawerLayout
import androidx.lifecycle.Observer
import androidx.navigation.Navigation
import androidx.navigation.ui.setupWithNavController
import com.google.android.material.navigation.NavigationView
import kotlinx.coroutines.*
import network.mysterium.service.core.DeferredNode
import network.mysterium.service.core.MysteriumAndroidCoreService
import network.mysterium.service.core.MysteriumCoreService
import network.mysterium.service.core.NetworkConnState
import network.mysterium.ui.Screen
import network.mysterium.ui.navigateTo
import network.mysterium.vpn.R

class TvActivity : AppCompatActivity() {

    private lateinit var appContainer: AppContainer
    private var deferredNode = DeferredNode()
    private var deferredMysteriumCoreService = CompletableDeferred<MysteriumCoreService>()
    private lateinit var vpnNotInternetLayout: LinearLayout

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceDisconnected(name: ComponentName?) {
            Log.i(TAG, "Service disconnected")
        }

        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            Log.i(TAG, "Service connected")
            deferredMysteriumCoreService.complete(service as MysteriumCoreService)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        setTheme(R.style.AppTheme)
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        vpnNotInternetLayout = findViewById(R.id.vpn_not_internet_layout)

        // Setup app drawer.
        val drawerLayout = findViewById<DrawerLayout>(R.id.drawer_layout)
        setupDrawerMenu(drawerLayout)

        // Initialize app DI container.
        appContainer = (application as MainApplication).appContainer
        appContainer.init(
                applicationContext,
                deferredNode,
                deferredMysteriumCoreService,
                drawerLayout,
                getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        )

        // Setup notifications.
        appContainer.appNotificationManager.init(this)

        // Bind VPN service.
        ensureVpnServicePermission()
        bindMysteriumService()

        // Start network connectivity checker and handle connection change event to
        // start mobile node and initial data when network is available.
        CoroutineScope(Dispatchers.Main).launch {
            val coreService = deferredMysteriumCoreService.await()
            coreService.startConnectivityChecker()
            coreService.networkConnState().observe(this@TvActivity, Observer {
                CoroutineScope(Dispatchers.Main).launch { handleConnChange(it) }
            })
        }

        // Navigate to main vpn screen and check if terms are accepted in separate coroutine
        // so it does not block main thread.
        navigate(R.id.main_vpn_fragment)
        CoroutineScope(Dispatchers.Main).launch {
            val termsAccepted = appContainer.termsViewModel.checkTermsAccepted()
            if (!termsAccepted) {
                navigate(R.id.terms_fragment)
            }
        }
    }

    override fun onDestroy() {
        unbindMysteriumService()
        super.onDestroy()
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)

        when (requestCode) {
            VPN_SERVICE_REQUEST -> {
                if (resultCode != Activity.RESULT_OK) {
                    Log.w(TAG, "User forbidden VPN service")
                    Toast.makeText(this, "VPN connection has to be granted for MysteriumVPN to work.", Toast.LENGTH_LONG).show()
                    finish()
                    return
                }
                Log.i(TAG, "User allowed VPN service")
            }
        }
    }

    // Start node and load initial data when connected to internet.
    private fun handleConnChange(networkConnState: NetworkConnState) {
        Log.i(TAG, "Network connection changed: $networkConnState")

        vpnNotInternetLayout.visibility = if (networkConnState.connected) {
            View.GONE
        } else {
            View.VISIBLE
        }

        startNode(networkConnState)
    }

    private fun startNode(networkConnState: NetworkConnState) {
        // Skip if node is already started.
        if (deferredNode.isStarted()) {
            return
        }

        // Skip if no internet connection.
        if (!networkConnState.connected) {
            return
        }

        // Start node in separate thread and load initial data.
        CoroutineScope(Dispatchers.Main).launch {
            deferredNode.start(deferredMysteriumCoreService.await()) { err ->
                if (err != null) {
                    showNodeStarError()
                }
            }
            loadInitialData()
        }
    }

    private suspend fun loadInitialData() {
        // Load initial data like current location, statistics, active proposal (if any).
        Log.i(TAG, "Loading shared data")
        val p1 = CoroutineScope(Dispatchers.Main).async { appContainer.sharedViewModel.load() }

        // Load initial proposals.
        Log.i(TAG, "Loading proposal data")
        val p2 = CoroutineScope(Dispatchers.Main).async { appContainer.proposalsViewModel.load() }

        // Load account data.
        Log.i(TAG, "Loading account data")
        val p3 = CoroutineScope(Dispatchers.Main).async { appContainer.accountViewModel.load() }

        awaitAll(p1, p2, p3)
    }

    private fun showNodeStarError() {
        Toast.makeText(this, "Failed to initialize. Please relaunch app.", Toast.LENGTH_LONG).show()
    }

    private fun bindMysteriumService() {
        Log.i(TAG, "Binding service")
        Intent(this, MysteriumAndroidCoreService::class.java).also { intent ->
            bindService(intent, serviceConnection, Context.BIND_AUTO_CREATE)
        }
    }

    private fun unbindMysteriumService() {
        Log.i(TAG, "Unbinding service")
        unbindService(serviceConnection)
    }

    private fun ensureVpnServicePermission() {
        val intent: Intent = VpnService.prepare(this) ?: return
        startActivityForResult(intent, VPN_SERVICE_REQUEST)
    }

    private fun setupDrawerMenu(drawerLayout: DrawerLayout) {
        val navController = Navigation.findNavController(this, R.id.nav_host_fragment)
        val navView = findViewById<NavigationView>(R.id.nav_view)
        navView.setupWithNavController(navController)
        navView.setNavigationItemSelectedListener {
            drawerLayout.closeDrawer(GravityCompat.START)
            when {
                it.itemId == R.id.menu_item_account -> navigateTo(navController, Screen.ACCOUNT)
                it.itemId == R.id.menu_item_feedback -> navigateTo(navController, Screen.FEEDBACK)
            }
            true
        }
    }

    private fun navigate(destination: Int) {
        val navController = Navigation.findNavController(this, R.id.nav_host_fragment)
        val navGraph = navController.navInflater.inflate(R.navigation.nav_graph)
        navGraph.startDestination = destination
        navController.graph = navGraph
    }

    companion object {
        private const val VPN_SERVICE_REQUEST = 1
        private const val TAG = "TvActivity"
    }
}