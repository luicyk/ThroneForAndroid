package io.nekohasekai.sagernet.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.view.View
import android.widget.TextView
import androidx.core.view.isVisible
import com.google.android.material.button.MaterialButton
import com.google.android.material.snackbar.Snackbar
import com.google.android.material.switchmaterial.SwitchMaterial
import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.database.DataStore
import io.nekohasekai.sagernet.database.SettingsRegistry
import io.nekohasekai.sagernet.databinding.LayoutLanShareBinding
import io.nekohasekai.sagernet.ktx.needReload
import io.nekohasekai.sagernet.widget.applyInsetPadding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import androidx.lifecycle.lifecycleScope
import java.net.Inet4Address
import java.net.NetworkInterface

/**
 * LAN Sharing: one screen for turning the mixed inbound's bind address off loopback and for showing the addresses other
 * devices on the same network should dial. The switch writes [DataStore.inboundAddress] exactly as the preference in
 * Inbound settings does, so both entry points stay in sync, and the app reloads the service to rebind.
 *
 * SOCKS5 and HTTP share the mixed inbound's port, so one address line covers both; the protocols are listed as
 * reference text rather than as a second set of rows.
 */
class LanShareActivity : ThemedActivity() {

    companion object {
        fun intent(context: Context): Intent = Intent(context, LanShareActivity::class.java)
    }

    private lateinit var binding: LayoutLanShareBinding
    private lateinit var allowLan: SwitchMaterial
    private lateinit var status: TextView
    private lateinit var container: android.widget.LinearLayout
    private lateinit var noAddress: TextView
    private lateinit var warning: TextView
    private lateinit var offHint: TextView
    private lateinit var copyButton: MaterialButton
    private lateinit var copied: TextView

    /** The addresses currently listed, for the clipboard. */
    private var shareLines: List<String> = emptyList()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = LayoutLanShareBinding.inflate(layoutInflater)
        setContentView(binding.root)
        binding.root.applyInsetPadding()

        setSupportActionBar(binding.toolbar)
        supportActionBar?.setHomeAsUpIndicator(R.drawable.baseline_arrow_back_24)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        binding.toolbar.setNavigationOnClickListener { finish() }

        allowLan = binding.switchAllowLan
        status = binding.status
        container = binding.addressContainer
        noAddress = binding.noAddress
        warning = binding.warning
        offHint = binding.offHint
        copyButton = binding.copyButton
        copied = binding.copied

        allowLan.isChecked = DataStore.allowLanAccess
        allowLan.setOnCheckedChangeListener { _, checked ->
            DataStore.inboundAddress =
                if (checked) SettingsRegistry.LAN_ADDRESS else SettingsRegistry.LOOPBACK_ADDRESS
            needReload()
            render()
        }
        copyButton.setOnClickListener { copyAddresses() }
    }

    override fun onResume() {
        super.onResume()
        render()
    }

    private fun render() {
        val sharing = DataStore.allowLanAccess
        val port = DataStore.inboundSocksPort
        val connected = DataStore.serviceState == io.nekohasekai.sagernet.bg.BaseService.State.Connected

        allowLan.isChecked = sharing
        // Risk and help copy only matter while the inbound is reachable from the network.
        warning.isVisible = sharing
        offHint.isVisible = !sharing
        status.isVisible = sharing
        copyButton.isVisible = sharing
        if (!sharing) {
            copied.isVisible = false
            container.removeAllViews()
            noAddress.isVisible = false
            status.text = ""
            return
        }

        status.text = if (connected) {
            getString(R.string.lan_share_status_running, port)
        } else {
            getString(R.string.lan_share_status_stopped, port)
        }

        lifecycleScope.launch {
            val addresses = withContext(Dispatchers.IO) { localAddresses() }
            container.removeAllViews()
            if (addresses.isEmpty()) {
                noAddress.isVisible = true
                copyButton.isEnabled = false
                shareLines = emptyList()
                return@launch
            }
            noAddress.isVisible = false
            copyButton.isEnabled = true
            shareLines = addresses.map { "$it:$port" }
            for (line in shareLines) {
                val row = layoutInflater.inflate(R.layout.layout_lan_share_item, container, false) as TextView
                row.text = line
                container.addView(row)
            }
        }
    }

    /**
     * IPv4 addresses of the interfaces that can actually reach other devices: a VPN/tun interface or a loopback would
     * not be dialable from the LAN. [Inet4Address] only, because the shared port is reached by typing an address.
     */
    private fun localAddresses(): List<String> {
        val result = ArrayList<String>()
        try {
            for (nic in NetworkInterface.getNetworkInterfaces()) {
                if (!nic.isUp || nic.isLoopback || nic.isVirtual) continue
                for (addr in nic.inetAddresses) {
                    if (addr is Inet4Address && !addr.isLoopbackAddress && !addr.isLinkLocalAddress) {
                        val text = addr.hostAddress ?: continue
                        if (text !in result) result.add(text)
                    }
                }
            }
        } catch (e: Exception) {
            // No readable interfaces: the caller shows the "not on a network" line.
        }
        result.sort()
        return result
    }

    private fun copyAddresses() {
        if (shareLines.isEmpty()) return
        val text = shareLines.joinToString("\n")
        getSystemService(ClipboardManager::class.java)
            ?.setPrimaryClip(ClipData.newPlainText(getString(R.string.lan_share_title), text))
        copied.isVisible = true
        copied.postDelayed({ copied.isVisible = false }, 2_000L)
    }
}