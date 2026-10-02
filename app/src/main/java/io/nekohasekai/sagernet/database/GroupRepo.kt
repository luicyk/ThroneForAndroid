package io.nekohasekai.sagernet.database

import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.SagerNet
import io.nekohasekai.sagernet.ktx.app
import java.util.concurrent.Callable
import java.util.concurrent.CopyOnWriteArrayList


/**
 * The id of the synthetic "All" tab: no `groups` row carries it, and [ProfileManager.members] answers it with
 * every profile instead of one group's. Negative so it can never collide with an auto-generated row id, and
 * small enough that [GroupRepo.get] (which rejects id <= 0) keeps ignoring it.
 */
const val ALL_GROUPS_ID = -1L

/**
 * The group repository (the desktop's GroupsRepo, GroupsRepo.cpp): the `groups` table in tab order
 * (`display_order`), the current group (`current_group`) and the Default group of an empty table.
 * Listeners are in-process; writes of the other process arrive through Room's multi-instance invalidation.
 */
object GroupRepo {

    interface Listener {
        suspend fun groupAdd(group: ProxyGroup)
        suspend fun groupUpdated(group: ProxyGroup)
        suspend fun groupRemoved(groupId: Long)

        /** The group's members changed (added, removed, reordered, sorted, cleared): reload them. */
        suspend fun groupUpdated(groupId: Long)

        /** The tab order changed. */
        suspend fun groupsReordered() {}

        /** The synthetic "All" tab was switched on or off; no `groups` row changed, so nothing else fires. */
        suspend fun allGroupsVisibilityChanged() {}
    }

    private val listeners = CopyOnWriteArrayList<Listener>()

    private val dao get() = SagerDatabase.groupDao

    fun addListener(listener: Listener) {
        listeners.addIfAbsent(listener)
    }

    fun removeListener(listener: Listener) {
        listeners.remove(listener)
    }

    private suspend fun notify(what: suspend Listener.() -> Unit) {
        for (listener in listeners) listener.what()
    }

    // ------------------------------------------------------------------------------------------------ reads

    /** Every group in tab order; an empty table first gets the Default group. */
    /** The "All" tab: a row with no `groups` entry behind it, so nothing can edit or delete it. */
    fun allGroup(): ProxyGroup = ProxyGroup(
        id = ALL_GROUPS_ID,
        name = app.getString(R.string.group_all),
    )

    /**
     * [all] with the "All" tab in front when it is switched on. Only the tab strip wants this; subscription
     * refresh, deletion checks and the groups screen must keep working on real rows alone.
     */
    fun allForDisplay(): List<ProxyGroup> =
        if (DataStore.showAllGroup) listOf(allGroup()) + all() else all()

    fun all(): List<ProxyGroup> {
        val groups = dao.allGroups()
        if (groups.isNotEmpty()) return groups
        ensureDefault()
        return dao.allGroups()
    }

    /** The group ids in tab order (GetGroupsTabOrder). */
    fun ids(): List<Long> {
        val ids = dao.allIds()
        if (ids.isNotEmpty()) return ids
        ensureDefault()
        return dao.allIds()
    }

    fun get(id: Long): ProxyGroup? = if (id > 0) dao.getById(id) else null

    /** current_group when it names a group, else the first group, which then becomes current. */
    fun currentId(): Long {
        val stored = SettingsRegistry.CURRENT_GROUP.read(DataStore.configurationStore)
        if (stored == ALL_GROUPS_ID && DataStore.showAllGroup) return stored
        if (stored > 0 && dao.getById(stored) != null) return stored
        val first = ids().first()
        setCurrent(first)
        return first
    }

    fun current(): ProxyGroup {
        val stored = SettingsRegistry.CURRENT_GROUP.read(DataStore.configurationStore)
        if (stored == ALL_GROUPS_ID && DataStore.showAllGroup) return allGroup()
        if (stored > 0) dao.getById(stored)?.let { return it }
        val first = all().first()
        setCurrent(first.id)
        return first
    }

    fun setCurrent(id: Long) {
        val store = DataStore.configurationStore
        if (SettingsRegistry.CURRENT_GROUP.read(store) != id) SettingsRegistry.CURRENT_GROUP.write(store, id)
    }

    /** Configs.cpp:35-39: the "Default" group of an empty table. */
/** After [io.nekohasekai.sagernet.database.DataStore.showAllGroup] flips; see [Listener.allGroupsVisibilityChanged]. */
    suspend fun postAllGroupsVisibilityChanged() {
        notify { allGroupsVisibilityChanged() }
    }

    fun ensureDefault() {
        dao.insertDefaultIfEmpty(SagerDatabase.defaultGroupName())
    }

    // ------------------------------------------------------------------------------------------------ writes

    /** GroupsRepo::AddGroup: a new id and the last tab (display_order = MAX + 1). */
    suspend fun add(group: ProxyGroup): ProxyGroup {
        SagerDatabase.instance.runInTransaction {
            group.id = 0L
            group.displayOrder = dao.nextDisplayOrder()
            group.id = dao.insert(group)
        }
        notify { groupAdd(group) }
        return group
    }

    suspend fun save(group: ProxyGroup) {
        dao.update(group)
        notify { groupUpdated(group) }
    }

    /**
     * GroupsRepo::DeleteGroup with the callers' rules: the last group is never deleted (returns false), a running
     * profile of the group is stopped first. The members go by the foreign key cascade.
     */
    suspend fun delete(id: Long): Boolean {
        val remaining = SagerDatabase.instance.runInTransaction(Callable {
            val ids = dao.allIds()
            if (id !in ids || ids.size <= 1) return@Callable null
            ids - id
        }) ?: return false
        val running = ProfileManager.runningProfileId()
        if (running > 0 && ProfileManager.getProfile(running)?.groupId == id) SagerNet.stopService()
        dao.deleteById(id)
        if (SettingsRegistry.CURRENT_GROUP.read(DataStore.configurationStore) == id) setCurrent(remaining.first())
        if (DataStore.selectedProxy > 0 && ProfileManager.getProfile(DataStore.selectedProxy) == null) {
            DataStore.selectedProxy = 0L
        }
        notify { groupRemoved(id) }
        return true
    }

    /** SetGroupsTabOrder: [ids] become the tabs 0..n-1; groups missing from [ids] follow in their current order. */
    suspend fun setDisplayOrder(ids: List<Long>) {
        SagerDatabase.instance.runInTransaction {
            val existing = dao.allIds()
            val ordered = ids.distinct().filter { it in existing } + existing.filter { it !in ids }
            ordered.forEachIndexed { index, groupId -> dao.setDisplayOrder(groupId, index.toLong()) }
        }
        notify { groupsReordered() }
    }

    /** Tells the listeners to reload the members of [groupId]. */
    suspend fun postReload(groupId: Long) {
        notify { groupUpdated(groupId) }
    }

    /** Tells the listeners that [group] changed (name, options...) without writing it. */
    suspend fun postUpdate(group: ProxyGroup) {
        notify { groupUpdated(group) }
    }

    suspend fun postUpdate(groupId: Long) {
        postUpdate(get(groupId) ?: return)
    }
}
