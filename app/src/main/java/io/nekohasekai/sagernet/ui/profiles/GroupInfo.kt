package io.nekohasekai.sagernet.ui.profiles

import android.content.Context
import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.database.ProxyGroup
import java.text.DateFormat
import java.util.Date
import java.util.Locale

/**
 * The subscription figures a group carries, formatted for display.
 *
 * Extracted from the Groups screen so that the line under the group tabs reads exactly like the one on a group card
 * instead of a second implementation that drifts.
 */
object GroupInfo {

    /** "Last update: …" and the Subscription-UserInfo line, one per line; empty when the group has neither. */
    fun infoText(context: Context, group: ProxyGroup): String {
        val lines = ArrayList<String>()
        if (group.subLastUpdate != 0L) {
            lines.add(context.getString(R.string.grp_last_update, displayTime(group.subLastUpdate)))
        }
        subInfo(context, group.info).takeIf { it.isNotEmpty() }?.let(lines::add)
        return lines.joinToString("\n")
    }

    /**
     * ParseSubInfo (GroupItem.cpp:13-50): used = upload + download, nothing without `total=`, ∞ for a zero total.
     * Unlike the desktop, a missing or zero expire is left out instead of printing the epoch.
     */
    fun subInfo(context: Context, info: String): String {
        if (info.isBlank()) return ""
        var used = 0L
        var total = 0L
        var expire = 0L
        var hasTotal = false
        for (match in SUB_INFO.findAll(info)) {
            val value = match.groupValues[2].toLongOrNull() ?: 0L
            when (match.groupValues[1]) {
                "total" -> {
                    total = value
                    hasTotal = true
                }

                "upload", "download" -> used += value
                "expire" -> expire = value
            }
        }
        if (!hasTotal) return ""
        val remain = if (total == 0L) "∞" else readableSize((total - used).coerceAtLeast(0L))
        return if (expire > 0L) {
            context.getString(R.string.grp_sub_info, readableSize(used), remain, displayTime(expire))
        } else {
            context.getString(R.string.grp_sub_info_no_expire, readableSize(used), remain)
        }
    }

    /** ReadableSize (Utils.cpp:221-241): 1024-based, two decimals. */
    fun readableSize(size: Long): String {
        var value = size.toDouble()
        var unit = 0
        while (value >= 1024.0 && unit < SIZE_UNITS.lastIndex) {
            value /= 1024.0
            unit++
        }
        return String.format(Locale.ROOT, "%.2f %s", value, SIZE_UNITS[unit])
    }

    /** DisplayTime(seconds, ShortFormat). */
    fun displayTime(seconds: Long): String =
        DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(seconds * 1000))

    private val SUB_INFO = Regex("(total|upload|download|expire)=([0-9]+)")
    private val SIZE_UNITS = arrayOf("B", "KiB", "MiB", "GiB", "TiB", "PiB", "EiB", "ZiB", "YiB")
}