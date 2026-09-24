/*
 * Copyright (C) 2016 Andrew Comminos <andrew@comminos.com>
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */

package se.lublin.mumla.app

import android.content.ComponentName
import android.content.Intent
import android.content.ServiceConnection
import android.content.SharedPreferences
import android.content.res.Configuration
import android.graphics.Color
import android.os.Bundle
import android.os.IBinder
import android.util.Log
import android.view.KeyEvent
import android.view.Menu
import android.view.MenuItem
import android.view.WindowManager
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.SystemBarStyle
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentTransaction
import androidx.lifecycle.lifecycleScope
import androidx.preference.PreferenceManager
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.launch
import se.lublin.humla.model.Server
import se.lublin.humla.session.HumlaEvent
import se.lublin.humla.util.MumbleURLParser
import se.lublin.mumla.BuildConfig
import se.lublin.mumla.R
import se.lublin.mumla.Settings
import se.lublin.mumla.channel.AccessTokenFragment
import se.lublin.mumla.channel.ChannelFragment
import se.lublin.mumla.channel.ServerInfoFragment
import se.lublin.mumla.chat.NoticeFormatter
import se.lublin.mumla.databinding.ActivityMainBinding
import se.lublin.mumla.db.MumlaDatabase
import se.lublin.mumla.db.MumlaRepository
import se.lublin.mumla.preference.generateDefaultCertificate
import se.lublin.mumla.preference.SettingsActivity
import se.lublin.mumla.servers.FavouriteServerListFragment
import se.lublin.mumla.servers.PublicServerListFragment
import se.lublin.mumla.servers.ServerEditFragment
import se.lublin.mumla.service.IMumlaService
import se.lublin.mumla.service.MumlaService
import se.lublin.mumla.util.Edge
import se.lublin.mumla.util.padForSystemBars
import java.net.MalformedURLException
import java.security.cert.X509Certificate

/**
 * The main screen: a drawer to pick between the server lists and the connected server's
 * screens, which it binds [MumlaService] for while resumed.
 */
@Suppress("TooManyFunctions") // Framework callbacks, each delegating to the classes that do the work.
class MumlaActivity :
    AppCompatActivity(),
    ServiceClient,
    ConnectionDialogs.Listener,
    SharedPreferences.OnSharedPreferenceChangeListener {

    private val serviceModel: ServiceViewModel by viewModels()
    private val service: IMumlaService? get() = serviceModel.service.value
    private val repository get() = MumlaRepository.get(this)

    private lateinit var settings: Settings
    private lateinit var drawer: MainDrawer
    private lateinit var dialogs: ConnectionDialogs
    private lateinit var connectFlow: ConnectFlow

    /** The dynamic colour setting this activity was themed with. */
    private var themedWithDynamicColors = false

    /** The service [onServiceBound] got, until [onServiceUnbound]. */
    private var boundService: IMumlaService? = null

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            serviceModel.attach((binder as? MumlaService.MumlaBinder)?.getService())
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            serviceModel.attach(null)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        settings = Settings.getInstance(this)
        themedWithDynamicColors = settings.isDynamicColorEnabled
        super.onCreate(savedInstanceState)
        // The app bar is dark in both themes.
        enableEdgeToEdge(statusBarStyle = SystemBarStyle.dark(Color.TRANSPARENT))
        val binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        binding.appBar.padForSystemBars(Edge.START, Edge.TOP, Edge.END)
        binding.contentFrame.padForSystemBars(Edge.START, Edge.END, Edge.BOTTOM, ime = true)
        binding.leftDrawer.padForSystemBars(Edge.START, Edge.TOP, Edge.BOTTOM)
        setSupportActionBar(binding.toolbar)
        onBackPressedDispatcher.addCallback(this, backCallback)

        setStayAwake(settings.shouldStayAwake)
        PreferenceManager.getDefaultSharedPreferences(this).registerOnSharedPreferenceChangeListener(this)

        drawer = MainDrawer(
            this, binding.drawerLayout, binding.leftDrawer, binding.toolbar,
            serverName = ::connectedServerName,
            onItemSelected = ::showDrawerFragment,
        )
        dialogs = ConnectionDialogs(this, settings, this)
        connectFlow = ConnectFlow(this, settings) { service }
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.setHomeButtonEnabled(true)

        serviceModel.bindClient(this, this)
        supportFragmentManager.setFragmentResultListener(ServerEditFragment.REQUEST_KEY, this) { _, result ->
            onServerEdited(ServerEditFragment.Result.from(result))
        }
        lifecycleScope.launch { serviceModel.isConnected.collect { backCallback.isEnabled = it } }
        lifecycleScope.launch {
            serviceModel.connectRequests.collect { request ->
                when (request) {
                    is ServerRequest.Favourite -> connectFlow.connect(request.server)
                    is ServerRequest.Public -> connectFlow.connectToPublic(request.server)
                }
            }
        }

        if (savedInstanceState == null) {
            showDrawerFragment(intent?.getIntExtra(EXTRA_DRAWER_FRAGMENT, FALLBACK_SCREEN) ?: FALLBACK_SCREEN)
        }
        if (intent?.action == Intent.ACTION_VIEW) offerServerFromUrl(intent.dataString)

        volumeControlStream = Settings.PLAYBACK_STREAM

        // Only on a real start, not when the activity is recreated, e.g. on rotation.
        if (savedInstanceState == null) {
            if (settings.isFirstRun) showFirstRunGuide() else StartupAction().execute(this)
        }
    }

    /** Offers to connect to the server a mumble:// [url] names. */
    private fun offerServerFromUrl(url: String?) {
        try {
            val server = MumbleURLParser.parseURL(url)
            ServerEditFragment.newInstance(server, ServerEditFragment.Action.CONNECT, true)
                .show(supportFragmentManager, "url_edit")
        } catch (e: MalformedURLException) {
            onBadUrl(e)
        } catch (e: NumberFormatException) {
            onBadUrl(e)
        }
    }

    private fun onBadUrl(e: Exception) {
        Log.w(TAG, "Could not parse the mumble:// URL", e)
        Toast.makeText(this, getString(R.string.mumble_url_parse_failed), Toast.LENGTH_LONG).show()
    }

    override fun onPostCreate(savedInstanceState: Bundle?) {
        super.onPostCreate(savedInstanceState)
        drawer.syncState()
    }

    override fun onResume() {
        super.onResume()
        // Changed in the settings screen, which recreates only itself.
        if (settings.isDynamicColorEnabled != themedWithDynamicColors) recreate()
        bindService(Intent(this, MumlaService::class.java), connection, 0)
    }

    override fun onPause() {
        super.onPause()
        dialogs.dismiss()
        serviceModel.attach(null)
        unbindService(connection)
    }

    override fun onDestroy() {
        PreferenceManager.getDefaultSharedPreferences(this).unregisterOnSharedPreferenceChangeListener(this)
        super.onDestroy()
    }

    override fun onServiceBound(service: IMumlaService) {
        boundService = service
        service.setSuppressNotifications(true)
        service.clearChatNotifications()
        drawer.refresh()
        if (showsConnectedScreen() && !service.isConnected) showDrawerFragment(DrawerAdapter.ITEM_FAVOURITES)
        dialogs.update(service)
    }

    override fun onServiceUnbound() {
        boundService?.setSuppressNotifications(false)
        boundService = null
    }

    override fun onServiceEvent(event: HumlaEvent) {
        val service = service ?: return
        when (event) {
            HumlaEvent.Connected -> {
                val pinned = settings.shouldStartUpInPinnedMode
                showDrawerFragment(if (pinned) DrawerAdapter.ITEM_PINNED_CHANNELS else DrawerAdapter.ITEM_SERVER)
                onConnectionChanged(service)
            }
            HumlaEvent.Connecting -> dialogs.update(service)
            is HumlaEvent.Disconnected -> {
                if (showsConnectedScreen()) showDrawerFragment(DrawerAdapter.ITEM_FAVOURITES)
                onConnectionChanged(service)
            }
            is HumlaEvent.TlsHandshakeFailed -> onUntrustedChain(service, event.chain, changed = false)
            is HumlaEvent.TlsCertificateChanged -> onUntrustedChain(service, event.chain, changed = true)
            is HumlaEvent.PermissionDenied -> dialogs.showPermissionDenied(NoticeFormatter(this).denial(event))
            else -> Unit
        }
    }

    /** Offers to trust the server's certificate, which is unknown or, if [changed], not the pinned one. */
    private fun onUntrustedChain(service: IMumlaService, chain: List<X509Certificate>, changed: Boolean) {
        val server = service.targetServer
        val certificate = chain.firstOrNull()
        if (server == null || certificate == null) return
        if (changed) {
            dialogs.showCertificateChanged(server, certificate)
        } else {
            dialogs.showUntrustedCertificate(server, certificate)
        }
    }

    private fun onConnectionChanged(service: IMumlaService) {
        drawer.refresh()
        invalidateOptionsMenu()
        dialogs.update(service)
    }

    /** Whether the content is a screen that needs a connection. */
    private fun showsConnectedScreen(): Boolean =
        when (supportFragmentManager.findFragmentById(R.id.content_frame)) {
            is ChannelFragment, is ServerInfoFragment, is AccessTokenFragment -> true
            else -> false
        }

    private fun connectedServerName(): String? {
        val server = service?.takeIf { it.isConnected }?.targetServer ?: return null
        return server.name.ifEmpty { server.host }
    }

    /** Enabled only while connected, so that back otherwise leaves with the predictive animation. */
    private val backCallback = object : OnBackPressedCallback(false) {
        override fun handleOnBackPressed() {
            service?.takeIf { it.isConnected }?.let(::confirmDisconnect)
        }
    }

    private fun confirmDisconnect(service: IMumlaService) {
        showConfirmDialog(getString(R.string.disconnectSure, service.targetServer?.name), R.string.confirm) {
            service.disconnect()
            showDrawerFragment(DrawerAdapter.ITEM_FAVOURITES)
        }
    }

    override fun onPrepareOptionsMenu(menu: Menu): Boolean {
        menu.findItem(R.id.action_disconnect).isVisible = service?.isConnected == true
        return super.onPrepareOptionsMenu(menu)
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.mumla, menu)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean = when {
        drawer.onOptionsItemSelected(item) -> true
        item.itemId == R.id.action_disconnect -> {
            service?.disconnect()
            true
        }
        else -> false
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        drawer.onConfigurationChanged(newConfig)
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        val service = service
        if (service != null && keyCode == settings.pushToTalkKey) {
            service.onTalkKeyDown()
            return true
        }
        return super.onKeyDown(keyCode, event)
    }

    override fun onKeyUp(keyCode: Int, event: KeyEvent): Boolean {
        val service = service
        if (service != null && keyCode == settings.pushToTalkKey) {
            service.onTalkKeyUp()
            return true
        }
        return super.onKeyUp(keyCode, event)
    }

    /** Offers to generate a client certificate, unless one is set up already. */
    private fun showFirstRunGuide() {
        if (settings.isUsingCertificate) {
            settings.isFirstRun = false
            return
        }
        var message = getString(R.string.first_run_generate_certificate)
        if (BuildConfig.FLAVOR == "donation") message = getString(R.string.donation_thanks) + "\n\n" + message
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.first_run_generate_certificate_title)
            .setMessage(message)
            .setPositiveButton(R.string.generate) { _, _ ->
                lifecycleScope.launch { generateDefaultCertificate() }
                settings.isFirstRun = false
            }
            .show()
    }

    /** Shows the screen of the drawer row [id]. */
    private fun showDrawerFragment(id: Int) {
        if (id == DrawerAdapter.ITEM_SETTINGS) {
            startActivity(Intent(this, SettingsActivity::class.java))
            return
        }
        val fragment = drawerFragment(id) ?: return
        supportFragmentManager.beginTransaction()
            .replace(R.id.content_frame, fragment, fragment.javaClass.name)
            .setTransition(FragmentTransaction.TRANSIT_FRAGMENT_FADE)
            .commit()
        supportActionBar?.title = drawer.title(id)
    }

    private fun drawerFragment(id: Int): Fragment? = when (id) {
        DrawerAdapter.ITEM_SERVER -> ChannelFragment()
        DrawerAdapter.ITEM_PINNED_CHANNELS ->
            ChannelFragment().apply { arguments = Bundle().apply { putBoolean("pinned", true) } }
        DrawerAdapter.ITEM_INFO -> ServerInfoFragment()
        DrawerAdapter.ITEM_ACCESS_TOKENS -> service?.targetServer?.id?.let(AccessTokenFragment::newInstance)
        DrawerAdapter.ITEM_FAVOURITES -> FavouriteServerListFragment()
        DrawerAdapter.ITEM_PUBLIC -> PublicServerListFragment()
        else -> null
    }

    private fun setStayAwake(stayAwake: Boolean) {
        if (stayAwake) {
            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        } else {
            window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
    }

    override fun onSharedPreferenceChanged(sharedPreferences: SharedPreferences, key: String?) {
        if (key == Settings.PREF_STAY_AWAKE) setStayAwake(settings.shouldStayAwake)
    }

    override fun reconnect(server: Server) = connectFlow.connect(server)

    override fun reconnectWithPassword(server: Server) {
        lifecycleScope.launch {
            if (server.isSaved) repository.io { updateServer(server) }
            connectFlow.connect(server)
        }
    }

    private fun onServerEdited(result: ServerEditFragment.Result) {
        val server = result.server
        when (result.action) {
            ServerEditFragment.Action.ADD -> saveThenShowFavourites { addServer(server) }
            ServerEditFragment.Action.EDIT -> saveThenShowFavourites { updateServer(server) }
            ServerEditFragment.Action.CONNECT -> connectFlow.connect(server)
        }
    }

    private fun saveThenShowFavourites(save: MumlaDatabase.() -> Unit) {
        lifecycleScope.launch {
            repository.io(save)
            showDrawerFragment(DrawerAdapter.ITEM_FAVOURITES)
        }
    }

    companion object {
        private const val TAG = "MumlaActivity"
        private const val FALLBACK_SCREEN = DrawerAdapter.ITEM_FAVOURITES

        /** The `DrawerAdapter.ITEM_*` id of the screen to show when the activity is created. */
        const val EXTRA_DRAWER_FRAGMENT = "drawer_fragment"
    }
}
