package io.nekohasekai.sagernet.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.Menu
import android.view.MenuItem
import android.widget.LinearLayout
import androidx.core.view.isVisible
import androidx.lifecycle.lifecycleScope
import com.google.android.material.snackbar.Snackbar
import com.google.android.material.switchmaterial.SwitchMaterial
import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.SagerNet
import io.nekohasekai.sagernet.bg.BaseService
import io.nekohasekai.sagernet.database.DataStore
import io.nekohasekai.sagernet.outbound.json.JsonInput
import io.nekohasekai.sagernet.database.SettingValidators
import io.nekohasekai.sagernet.database.SettingsRegistry
import io.nekohasekai.sagernet.databinding.LayoutLanShareBinding
import io.nekohasekai.sagernet.databinding.LayoutLanShareClientBinding
import io.nekohasekai.sagernet.databinding.LayoutLanSharePlanBinding
import io.nekohasekai.sagernet.utils.LanClients
import io.nekohasekai.sagernet.ui.json.JsonEditorActivity
import io.nekohasekai.sagernet.widget.applyInsetPadding
import java.net.Inet4Address
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * LAN Sharing: the master switch, the two ways another device can reach this phone, and who is connected right now.
 *
 * Plan A is the hotspot case, where every client dials the hotspot interface's own address; Plan B is the shared
 * Wi-Fi case, where the address is whatever the wlan interface holds. No public API reports the hotspot state,
 * so [LanClients.hotspot] has to infer it; that function's comment says what it can and cannot tell, and why "off" is
 * only reported once something has actually answered.
 *
 * The client list reads /proc/net/tcp rather than the core: [io.nekohasekai.sagernet.aidl.ISagerNetService] only reports
 * traffic per profile. That table gives addresses and connection counts, not byte totals, so the rows carry no rates.
 * When the table is unreadable the screen says so instead of reporting no clients.
 */
class LanShareActivity : ThemedActivity() {

    companion object {
        private const val CLIENT_REFRESH_MS = 3_000L

        fun intent(context: Context): Intent = Intent(context, LanShareActivity::class.java)
    }

    private lateinit var binding: LayoutLanShareBinding
    private lateinit var planA: LayoutLanSharePlanBinding
    private lateinit var planB: LayoutLanSharePlanBinding
    private lateinit var allowLan: SwitchMaterial

    /** Set while [render] writes the switch, so the listener does not treat it as a user edit. */
    private var updatingSwitch = false

    /** The port on screen, so a random port drawn by the service is noticed on the next tick. */
    private var portShown = -1

    /** The hotspot state of the last poll; null until one lands, which the badge reads as unknown. */
    private var hotspot: LanClients.Hotspot? = null

    /**
     * The hotspot interface's own address, which is what a client on the hotspot dials. Null until a poll finds one,
     * and while there is none the Plan A host reads "no address" rather than a plausible-looking constant: AOSP hands
     * out 192.168.43.1, but the subnet is a carrier and vendor choice and this phone uses 10.32.206.x.
     */
    private var hotspotAddress: java.net.Inet4Address? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = LayoutLanShareBinding.inflate(layoutInflater)
        setContentView(binding.root)
        binding.root.applyInsetPadding(top = true)
        binding.toolbar.applyInsetPadding(horizontal = true)

        setSupportActionBar(binding.toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.setHomeAsUpIndicator(R.drawable.baseline_arrow_back_24)
        binding.toolbar.setNavigationOnClickListener { finish() }

        // The two plans are <include>d with an id, so ViewBinding already exposes them as bindings.
        planA = binding.planA
        planB = binding.planB
        allowLan = binding.switchAllowLan

        allowLan.setOnCheckedChangeListener { _, checked ->
            if (updatingSwitch) return@setOnCheckedChangeListener
            DataStore.inboundAddress =
                if (checked) SettingsRegistry.LAN_ADDRESS else SettingsRegistry.LOOPBACK_ADDRESS
            render()
            SagerNet.reloadService()
        }

        // The service itself, which the bind address above does not start. ServiceButton only lets its
        // toggle be touched when the state can be stopped or is fully stopped, and this follows that.
        binding.serviceRow.setOnClickListener {
            if (binding.switchService.isEnabled) binding.switchService.toggle()
        }
        binding.switchService.setOnCheckedChangeListener { _, checked ->
            if (updatingSwitch) return@setOnCheckedChangeListener
            // canStop is false while connecting or stopping, so a stale tap cannot restart mid-teardown.
            if (DataStore.serviceState.canStop) SagerNet.stopService() else SagerNet.startService()
            render()
        }

        planA.copyHost.setOnClickListener { copy(getString(R.string.lan_share_copied_host), planA.planHost.text.toString()) }
        planA.copyPort.setOnClickListener { copy(getString(R.string.lan_share_copied_port), planA.planPort.text.toString()) }
        planB.copyHost.setOnClickListener { copy(getString(R.string.lan_share_copied_host), planB.planHost.text.toString()) }
        planB.copyPort.setOnClickListener { copy(getString(R.string.lan_share_copied_port), planB.planPort.text.toString()) }
        binding.refresh.setOnClickListener { refreshClients() }
        binding.authRow.setOnClickListener { openAuthSettings() }
binding.customInboundRow.setOnClickListener { openCustomInbound() }
        // The row carries the label and the summary, so the whole strip is the switch target.
        binding.portRandomRow.setOnClickListener { binding.portRandom.isChecked = !binding.portRandom.isChecked }
binding.portRandom.setOnCheckedChangeListener { _, checked ->
if (updatingSwitch) return@setOnCheckedChangeListener
DataStore.randomInboundPort = checked
SagerNet.reloadService()
render()
}
        binding.portInput.setOnEditorActionListener { _, _, _ ->
            commitPort()
            true
        }
binding.portInput.setOnFocusChangeListener { _, hasFocus ->
if (hasFocus || updatingSwitch) return@setOnFocusChangeListener
commitPort()
}
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.lan_share_menu, menu)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            R.id.lan_share_refresh -> {
                refreshClients()
                true
            }
            else -> super.onOptionsItemSelected(item)
        }
    }

    override fun onResume() {
        super.onResume()
        render()
        // Poll while the screen is up: there is no callback for another device opening a connection.
        lifecycleScope.launch {
            while (isActive) {
                // A random port is only drawn once the service rebuilds its config (BoxInstance.buildConfig),
                // which happens after reloadService has been through a broadcast. Pick it up on the next tick
                // rather than leaving a stale port on screen until the user leaves and comes back.
                if (portShown != DataStore.inboundSocksPort && !binding.portInput.hasFocus()) render()
                refreshClients()
                delay(CLIENT_REFRESH_MS)
            }
        }
    }

    private fun render() {
        val sharing = DataStore.allowLanAccess
        val port = DataStore.inboundSocksPort

        updatingSwitch = true
        allowLan.isChecked = sharing
        updatingSwitch = false

        binding.switchSummary.text =
            getString(R.string.lan_share_inbound_summary, DataStore.inboundAddress)
        updatingSwitch = true
        binding.portRandom.isChecked = DataStore.randomInboundPort
        // Never clobber a port mid-edit: commitPort() runs on focus loss, so overwriting here would drop
        // whatever was typed. isEnabled still runs every time, that is what turning the random port off
        // depends on.
        if (!binding.portInput.hasFocus()) binding.portInput.setText(port.toString())
        binding.portInput.isEnabled = !DataStore.randomInboundPort
        binding.customInboundSummary.text = customInboundSummary()
        updatingSwitch = false

        // Same state -> icon mapping ServiceButton uses on the main screen.
        val state = DataStore.serviceState
        updatingSwitch = true
        binding.serviceIcon.setImageResource(
            when (state) {
                BaseService.State.Connecting -> R.drawable.ic_service_connecting
                BaseService.State.Connected -> R.drawable.ic_service_connected
                BaseService.State.Stopping -> R.drawable.ic_service_stopping
                else -> R.drawable.ic_service_stopped
            },
        )
        binding.serviceSummary.text =
            getString(if (state.connected) R.string.lan_share_running else R.string.lan_share_stopped)
        binding.switchService.isChecked = state.started
        binding.switchService.isEnabled = state.canStop || state == BaseService.State.Stopped
        updatingSwitch = false

        binding.authSummary.text = if (DataStore.inboundAuth) {
            getString(R.string.lan_share_auth_on, DataStore.inboundUser)
        } else {
            getString(R.string.lan_share_auth_off)
        }

        portShown = port

        val portText = port.toString()
        binding.headlineState.text = getString(if (sharing) R.string.lan_share_state_on else R.string.lan_share_state_off)
        binding.headlineDetail.text = getString(R.string.lan_share_headline_detail, port, authLabel())

        bindPlan(
            planA,
            icon = R.drawable.ic_hardware_router,
            title = R.string.lan_share_plan_a,
            note = getString(R.string.lan_share_plan_a_note),
            host = hotspotAddress?.hostAddress ?: getString(R.string.lan_share_no_address),
            port = portText,
            badge = hotspotLabel(),
            badgeActive = hotspot == LanClients.Hotspot.ON,
        )
        bindPlan(
            planB,
            icon = R.drawable.ic_baseline_block_24,
            title = R.string.lan_share_plan_b,
            note = getString(R.string.lan_share_plan_b_note),
            host = wifiAddress ?: getString(R.string.lan_share_no_address),
            port = portText,
            badge = wifiAddress?.let { getString(R.string.lan_share_wifi_connected) }
                ?: getString(R.string.lan_share_wifi_unknown),
            badgeActive = wifiAddress != null,
        )

        binding.clientsEmpty.isVisible = false
        binding.clientsContainer.isVisible = false
        binding.clientsHeader.isVisible = sharing
        binding.refresh.isEnabled = sharing
    }

    /** The Plan A badge, which is the hotspot state of the last poll rather than a fixed "unknown". */
    private fun hotspotLabel(): String = when (hotspot) {
        LanClients.Hotspot.ON -> getString(R.string.lan_share_hotspot_on)
        LanClients.Hotspot.OFF -> getString(R.string.lan_share_hotspot_off)
        LanClients.Hotspot.UNKNOWN, null -> getString(R.string.lan_share_hotspot_unknown)
    }

    private fun bindPlan(
        plan: LayoutLanSharePlanBinding,
        icon: Int,
        title: Int,
        note: String,
        host: String,
        port: String,
        badge: String,
        badgeActive: Boolean = false,
    ) {
        plan.planIcon.setImageResource(icon)
        plan.planTitle.setText(title)
        plan.planNote.text = note
        plan.planHost.text = host
        plan.planPort.text = port
        plan.planBadge.text = badge
        plan.planBadge.isVisible = badge.isNotEmpty()
        plan.planBadge.alpha = if (badgeActive) 1f else 0.7f
        // Nothing to dial while the inbound is loopback-only.
        val usable = DataStore.allowLanAccess
        plan.copyHost.isEnabled = usable
        plan.copyPort.isEnabled = usable
        plan.planHost.alpha = if (usable) 1f else 0.5f
    }

        /** The auth summary for the headline and the auth row; auth_on carries the user name, so it needs the argument. */
    private fun authLabel(): String = if (DataStore.inboundAuth) {
        getString(R.string.lan_share_auth_on, DataStore.inboundUser)
    } else {
        getString(R.string.lan_share_auth_off)
    }

    /** The wlan address, which is what a client on the same router dials; null until the first poll lands. */
    private var wifiAddress: String? = null

    private fun refreshClients() {
        if (!DataStore.allowLanAccess) {
            binding.clientsContainer.removeAllViews()
            binding.clientsEmpty.isVisible = true
            binding.clientsEmpty.setText(R.string.lan_share_clients_off)
            binding.clientsSummary.text = ""
            return
        }
        lifecycleScope.launch {
            // Three things to read off the interfaces, all cheap and all blocking, so they go to IO together.
            val wlan = withContext(Dispatchers.IO) { wlanAddress() }
            val spot = withContext(Dispatchers.IO) { LanClients.hotspot(this@LanShareActivity) }
            val ap = withContext(Dispatchers.IO) { LanClients.hotspotAddress() }
            val changed = spot != hotspot || ap != hotspotAddress
            wifiAddress = wlan
            hotspot = spot
            hotspotAddress = ap
            if (changed) render()
            val port = DataStore.inboundSocksPort
            when (val table = withContext(Dispatchers.IO) { LanClients.sample(port) }) {
                // An unreadable table says nothing about who is connected, and saying "no client is connected" would
                // be a claim the screen cannot support.
                is LanClients.Table.UNREADABLE -> {
                    binding.clientsContainer.removeAllViews()
                    binding.clientsContainer.isVisible = false
                    binding.clientsEmpty.isVisible = true
                    binding.clientsEmpty.setText(R.string.lan_share_clients_unreadable)
                    binding.clientsSummary.text = ""
                }
                is LanClients.Table.Clients -> {
                    val entries = table.entries
                    if (entries.isEmpty()) {
                        binding.clientsContainer.removeAllViews()
                        binding.clientsContainer.isVisible = false
                        binding.clientsEmpty.isVisible = true
                        binding.clientsEmpty.setText(R.string.lan_share_clients_empty)
                        binding.clientsSummary.text = ""
                        return@launch
                    }
                    binding.clientsEmpty.isVisible = false
                    binding.clientsContainer.isVisible = true
                    binding.clientsSummary.text = resources.getQuantityString(
                        R.plurals.lan_share_clients_count, entries.size, entries.size
                    )
                    renderClients(entries)
                }
            }
        }
    }

    /**
     * The address of the wlan interface when there is one, else the first shareable address.
     *
     * NetworkInterface.getInetAddresses() is an Enumeration, not a Collection, so membership is checked by walking it.
     */
    private fun wlanAddress(): String? {
        val all = LanClients.shareableAddresses()
        if (all.isEmpty()) return null
        var wlan: Inet4Address? = null
        var fallback: Inet4Address? = null
        val nics = runCatching { java.net.NetworkInterface.getNetworkInterfaces() }.getOrNull()
            ?: return all.first().hostAddress
        while (nics.hasMoreElements()) {
            val nic = nics.nextElement()
            val onThisNic = all.filter { address -> nic.hasAddress(address) }
            if (onThisNic.isEmpty()) continue
            if (fallback == null) fallback = onThisNic.first()
            if (nic.name.startsWith("wlan") && wlan == null) wlan = onThisNic.first()
        }
        return (wlan ?: fallback ?: all.first()).hostAddress
    }

    /** [NetworkInterface.getInetAddresses] is an [Enumeration], so it has to be walked to test membership. */
    private fun java.net.NetworkInterface.hasAddress(address: Inet4Address): Boolean {
        val addresses: java.util.Enumeration<java.net.InetAddress> = getInetAddresses() ?: return false
        while (addresses.hasMoreElements()) {
            if (addresses.nextElement() == address) return true
        }
        return false
    }

    private fun renderClients(entries: Map<String, LanClients.Entry>) {
        val container = binding.clientsContainer
        // Reuse the rows instead of rebuilding: refreshing every few seconds must not flicker.
        while (container.childCount > entries.size) container.removeViewAt(container.childCount - 1)
        var index = 0
        for (entry in entries.values) {
            val row = if (index < container.childCount) {
                LayoutLanShareClientBinding.bind(container.getChildAt(index))
            } else {
                LayoutLanShareClientBinding.bind(
                    LayoutInflater.from(this).inflate(R.layout.layout_lan_share_client, container, false)
                ).also { container.addView(it.root) }
            }
            row.clientAddress.text = entry.address
            row.clientDetail.text = getString(R.string.lan_share_client_detail, entry.connections)
            index++
        }
    }

    /** "empty" until the override carries at least one inbound, otherwise a short description of what it holds. */
    private fun customInboundSummary(): String {
        val raw = DataStore.customInbound.trim()
        if (raw.isEmpty() || raw == "{}") return getString(R.string.lan_share_custom_empty)
        val count = runCatching { JsonInput.parseObject(raw).array("inbounds").size }.getOrDefault(-1)
        return if (count > 0) {
            resources.getQuantityString(R.plurals.lan_share_custom_count, count, count)
        } else {
            getString(R.string.lan_share_custom_present)
        }
    }

    /** Validates and stores the typed port; anything unparsable is put back the way it was. */
    private fun commitPort() {
        val raw = binding.portInput.text.toString().trim()
        val port = raw.toIntOrNull()
        if (port == null || !SettingValidators.isPort(port)) {
            binding.portInput.setText(DataStore.inboundSocksPort.toString())
            Snackbar.make(binding.root, getString(R.string.invalid_port, raw), Snackbar.LENGTH_LONG).show()
            return
        }
        if (port == DataStore.inboundSocksPort) return
        DataStore.inboundSocksPort = port
        portShown = -1
        SagerNet.reloadService()
        render()
    }

    /** The raw inbound JSON, edited in the project's own JSON editor. */
    private fun openCustomInbound() {
        val intent = JsonEditorActivity.intent(
            this,
            SettingsRegistry.CUSTOM_INBOUND.key,
            useConfigStore = true,
        )
        runCatching { startActivity(intent) }
    }

    private fun copy(message: String, text: String) {
        if (text.isEmpty()) return
        getSystemService(ClipboardManager::class.java)
            ?.setPrimaryClip(ClipData.newPlainText(getString(R.string.lan_share_title), text))
        Snackbar.make(binding.root, message, Snackbar.LENGTH_SHORT).show()
    }

    /**
 * Authentication is edited in place: the preference that owns it lives inside a settings sub-screen this Activity
 * cannot navigate to, and a dialog keeps the flow on the page the user is already looking at.
 */
    private fun openAuthSettings() {
        val context = this
        val enable = com.google.android.material.checkbox.MaterialCheckBox(context).apply {
            isChecked = DataStore.inboundAuth
        }
        val user = android.widget.EditText(context).apply {
            setText(DataStore.inboundUser)
            hint = getString(R.string.username)
            isEnabled = enable.isChecked
        }
        val pass = android.widget.EditText(context).apply {
            setText(DataStore.inboundPass)
            hint = getString(R.string.password)
            inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD
            isEnabled = enable.isChecked
        }
        enable.setOnCheckedChangeListener { _, checked ->
            user.isEnabled = checked
            pass.isEnabled = checked
        }
        val form = android.widget.LinearLayout(context).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            val pad = (20 * resources.displayMetrics.density).toInt()
            setPadding(pad, pad / 2, pad, 0)
            addView(enable)
            addView(user)
            addView(pass)
        }
        com.google.android.material.dialog.MaterialAlertDialogBuilder(context)
            .setTitle(R.string.cag_authentication)
            .setView(form)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                DataStore.inboundAuth = enable.isChecked
                if (enable.isChecked) {
                    DataStore.inboundUser = user.text.toString().trim()
                    DataStore.inboundPass = pass.text.toString()
                }
                SagerNet.reloadService()
                render()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }
}
