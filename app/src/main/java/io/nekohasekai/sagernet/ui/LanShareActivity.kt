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
import io.nekohasekai.sagernet.database.SettingsRegistry
import io.nekohasekai.sagernet.databinding.LayoutLanShareBinding
import io.nekohasekai.sagernet.databinding.LayoutLanShareClientBinding
import io.nekohasekai.sagernet.databinding.LayoutLanSharePlanBinding
import io.nekohasekai.sagernet.utils.LanClients
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
 * Plan A is the hotspot case, where the phone hands out 192.168.43.1 and every client dials that; Plan B is the shared
 * Wi-Fi case, where the address is whatever the wlan interface holds. Android gives an app no way to learn whether the
 * hotspot is on (that needs TETHER_PRIVILEGED), so Plan A carries a note instead of a state chip.
 *
 * The client list reads /proc/net/tcp rather than the core: [io.nekohasekai.sagernet.aidl.ISagerNetService] only reports
 * traffic per profile. See [LanClients] for what that costs. When the table is unreadable the list degrades to the
 * addresses alone instead of failing.
 */
class LanShareActivity : ThemedActivity() {

    companion object {
        /** Android's default hotspot gateway; the phone is the server when it is the hotspot. */
        const val HOTSPOT_GATEWAY = "192.168.43.1"

        private const val CLIENT_REFRESH_MS = 3_000L

        fun intent(context: Context): Intent = Intent(context, LanShareActivity::class.java)
    }

    private lateinit var binding: LayoutLanShareBinding
    private lateinit var planA: LayoutLanSharePlanBinding
    private lateinit var planB: LayoutLanSharePlanBinding
    private lateinit var allowLan: SwitchMaterial

    /** Set while [render] writes the switch, so the listener does not treat it as a user edit. */
    private var updatingSwitch = false
    private var boundPort = -1

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

        planA.copyHost.setOnClickListener { copy(getString(R.string.lan_share_copied_host), planA.planHost.text.toString()) }
        planA.copyPort.setOnClickListener { copy(getString(R.string.lan_share_copied_port), planA.planPort.text.toString()) }
        planB.copyHost.setOnClickListener { copy(getString(R.string.lan_share_copied_host), planB.planHost.text.toString()) }
        planB.copyPort.setOnClickListener { copy(getString(R.string.lan_share_copied_port), planB.planPort.text.toString()) }
        binding.refresh.setOnClickListener { refreshClients() }
        binding.authRow.setOnClickListener { openAuthSettings() }
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
                refreshClients()
                delay(CLIENT_REFRESH_MS)
            }
        }
    }

    private fun render() {
        val sharing = DataStore.allowLanAccess
        val port = DataStore.inboundSocksPort
        val connected = DataStore.serviceState == BaseService.State.Connected

        updatingSwitch = true
        allowLan.isChecked = sharing
        updatingSwitch = false

        binding.serviceState.text = getString(if (connected) R.string.lan_share_running else R.string.lan_share_stopped)
        binding.switchSummary.text =
            getString(R.string.lan_share_inbound_summary, DataStore.inboundAddress)
        binding.authSummary.text = if (DataStore.inboundAuth) {
            getString(R.string.lan_share_auth_on, DataStore.inboundUser)
        } else {
            getString(R.string.lan_share_auth_off)
        }

        if (boundPort != port) {
            // The counter differencing is per port; a new port invalidates the previous sample.
            LanClients.reset()
            boundPort = port
        }

        val portText = port.toString()
        binding.headlineState.text = getString(if (sharing) R.string.lan_share_state_on else R.string.lan_share_state_off)
        binding.headlineDetail.text = getString(R.string.lan_share_headline_detail, port) + authSuffix()

        bindPlan(
            planA,
            icon = R.drawable.ic_hardware_router,
            title = R.string.lan_share_plan_a,
            note = getString(R.string.lan_share_plan_a_note, HOTSPOT_GATEWAY),
            host = HOTSPOT_GATEWAY,
            port = portText,
            badge = getString(R.string.lan_share_hotspot_unknown),
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

    private fun authSuffix(): String {
        val auth = getString(
            if (DataStore.inboundAuth) R.string.lan_share_auth_on else R.string.lan_share_auth_off
        )
        return " · ${getString(R.string.lan_share_auth)} $auth"
    }

    /** The wlan address, which is what a client on the same router dials; null until the first poll lands. */
    private var wifiAddress: String? = null

    private fun refreshClients() {
        if (!DataStore.allowLanAccess) {
            binding.clientsContainer.removeAllViews()
            binding.clientsEmpty.isVisible = true
            binding.clientsSummary.text = ""
            return
        }
        lifecycleScope.launch {
            val wlan = withContext(Dispatchers.IO) { wlanAddress() }
            if (wlan != wifiAddress) {
                wifiAddress = wlan
                if (wlan != null) render()
            }
            val port = DataStore.inboundSocksPort
            val entries = withContext(Dispatchers.IO) { LanClients.sample(port) }
            if (entries.isNullOrEmpty()) {
                binding.clientsContainer.removeAllViews()
                binding.clientsContainer.isVisible = false
                binding.clientsEmpty.isVisible = true
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
        val addresses = addresses ?: return false
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
            row.clientUp.text = "▲ " + formatRate(entry.up)
            row.clientDown.text = "▼ " + formatRate(entry.down)
            index++
        }
    }

    private fun formatRate(bytes: Long?): String {
        if (bytes == null || bytes < 0) return "-"
        val units = arrayOf("B", "KB", "MB", "GB")
        var value = bytes.toDouble()
        var unit = 0
        while (value >= 1024 && unit < units.size - 1) {
            value /= 1024
            unit++
        }
        return if (unit == 0) String.format("%.0f %s", value, units[unit])
        else String.format("%.2f %s", value, units[unit])
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