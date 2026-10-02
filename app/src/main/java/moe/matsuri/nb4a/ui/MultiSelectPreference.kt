package moe.matsuri.nb4a.ui

import android.content.Context
import android.util.AttributeSet
import androidx.preference.Preference
import com.google.android.material.dialog.MaterialAlertDialogBuilder

/**
 * A list preference that keeps several values at once, stored newline-separated in the preference's own string value.
 *
 * The project only ships [SimpleMenuPreference] and [LongClickListPreference], both single choice. The auto-selector's
 * "Servers from" needs several groups, and a dropdown full of checkboxes is unusable, so this opens a multi choice
 * dialog instead.
 *
 * Newline is the separator because [moe.matsuri.nb4a.proxy.PreferenceBinding] already reads a List field as
 * newline-joined text, so a bound `List<Long>` needs no special casing on either side.
 *
 * Entries are set in code rather than from XML, the same way [SimpleMenuPreference] is used elsewhere, so [entries] and
 * [entryValues] are assigned after inflation.
 */
open class MultiSelectPreference
@JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = androidx.preference.R.attr.preferenceStyle,
    defStyleRes: Int = 0,
) : Preference(context, attrs, defStyleAttr, defStyleRes) {

    /** The persisted string; [Preference] has no readable value of its own, only [getPersistedString]. */
    private var stored: String? = null

    /**
     * androidx only calls [onSetInitialValue] when the XML carries a defaultValue, which this preference does not,
     * so the persisted string is read the first time it is needed instead of waiting for that callback.
     */
    private fun persisted(): String {
        stored?.let { return it }
        return (getPersistedString(null) ?: "").also { stored = it }
    }

    /** The checkbox state while the dialog is open, and what a refused change is measured against. */
    private var pending: List<String> = emptyList()

    /** Assigning either re-labels the summary, since the entries arrive after inflation. */
    var entries: Array<CharSequence> = emptyArray()
        set(value) {
            field = value
            refreshSummary(selectedValues())
        }

    var entryValues: Array<CharSequence> = emptyArray()
        set(value) {
            field = value
            refreshSummary(selectedValues())
        }

    init {
        isPersistent = true
        summary = ""
    }

    /** The stored values, in the order they were chosen. Empty when nothing is chosen. */
    fun selectedValues(): List<String> = persisted().split('\n').map { it.trim() }.filter { it.isNotEmpty() }

    fun setSelectedValues(values: Collection<String>) {
        val joined = values.joinToString("\n")
        // A listener that refuses the change must not leave the persisted value or the summary ahead of the dialog.
        if (!callChangeListener(joined)) return
        stored = joined
        persistString(joined)
        refreshSummary(values)
    }

    override fun onSetInitialValue(defaultValue: Any?) {
        // Reading here is what seeds the dialog on the first open after a restore.
        stored = getPersistedString(defaultValue as? String ?: null)
    }

    override fun onClick() {
        if (entries.isEmpty() || entryValues.isEmpty()) return
        pending = selectedValues()
        val checked = BooleanArray(entries.size) { entryValues[it].toString() in pending }
        MaterialAlertDialogBuilder(context)
            .setTitle(title)
            .setMultiChoiceItems(entries, checked) { _, which, isChecked ->
                val value = entryValues[which].toString()
                pending = if (isChecked) {
                    if (value in pending) pending else pending + value
                } else {
                    pending - value
                }
            }
            .setPositiveButton(android.R.string.ok) { _, _ -> setSelectedValues(pending) }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun refreshSummary(chosen: Collection<String>) {
        // entries is declared before entryValues, so its initialiser runs while the other is still null.
        if (chosen.isEmpty() || entries.isEmpty()) {
            summary = ""
            return
        }
        summary = when {
            // More than one shows the names, since the ids mean nothing to a reader.
            else -> chosen.joinToString(", ") { entryLabel(it) }
        }
    }

    private fun entryLabel(value: String): String {
        val index = entryValues.indexOfFirst { it.toString() == value }
        return if (index >= 0) entries[index].toString() else value
    }
}
