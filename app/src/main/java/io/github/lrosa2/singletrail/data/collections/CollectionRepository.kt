package io.github.lrosa2.singletrail.data.collections

import android.database.sqlite.SQLiteConstraintException
import io.github.lrosa2.singletrail.data.db.CollectionCount
import io.github.lrosa2.singletrail.data.db.CollectionEntity
import io.github.lrosa2.singletrail.data.db.RouteCollectionEntity
import io.github.lrosa2.singletrail.data.db.SingletrailDatabase
import io.github.lrosa2.singletrail.importing.ImportCollections
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import java.util.UUID

/** Collections of routes (spec §8.1, §8.2, D48): a route can be in several; names are unique ignoring case. */
class CollectionRepository(
    db: SingletrailDatabase,
    private val clock: () -> Long = System::currentTimeMillis,
) : ImportCollections {
    private val dao = db.collections()

    /** Collections A–Z with their route counts. */
    fun observeCounts(): Flow<List<CollectionCount>> = dao.observeCounts()

    /** Route ids per collection id; collections without routes are absent. */
    fun observeMembers(): Flow<Map<String, Set<String>>> = dao.observeMemberships().map { rows ->
        rows.groupBy({ it.collectionId }, { it.routeId }).mapValues { it.value.toSet() }
    }

    fun observeForRoute(routeId: String): Flow<List<CollectionEntity>> = dao.observeForRoute(routeId)

    suspend fun all(): List<CollectionEntity> = dao.all()

    /** The names of each route's collections, by route id (for full backups). */
    suspend fun namesByRoute(): Map<String, List<String>> {
        val names = dao.all().associate { it.id to it.name }
        return dao.memberships().groupBy({ it.routeId }, { names.getValue(it.collectionId) })
    }

    /** The collection called [name] (ignoring case and extra spaces), created when there is none; null for a blank name. */
    suspend fun named(name: String): CollectionEntity? {
        val clean = CollectionNames.clean(name) ?: return null
        find(clean)?.let { return it }
        val created = CollectionEntity(UUID.randomUUID().toString(), clean, clock())
        return try {
            dao.insert(created)
            created
        } catch (_: SQLiteConstraintException) {
            find(clean) // Made at the same moment elsewhere.
        }
    }

    /** False when [name] is blank or another collection already has it. */
    suspend fun rename(id: String, name: String): Boolean {
        val clean = CollectionNames.clean(name) ?: return false
        val other = find(clean)
        if (other != null && other.id != id) return false
        dao.rename(id, clean)
        return true
    }

    /** Removes the collection; its routes stay in the library (spec §8.6). */
    suspend fun delete(id: String) = dao.delete(id)

    suspend fun add(collectionId: String, routeIds: Collection<String>) {
        if (routeIds.isEmpty()) return
        val now = clock()
        dao.addMembers(routeIds.distinct().map { RouteCollectionEntity(it, collectionId, now) })
    }

    suspend fun remove(collectionId: String, routeIds: Collection<String>) {
        if (routeIds.isNotEmpty()) dao.removeMembers(collectionId, routeIds.toList())
    }

    /** Puts the route in exactly [collectionIds] (the route page's checklist). */
    suspend fun setForRoute(routeId: String, collectionIds: Set<String>) =
        dao.setForRoute(routeId, collectionIds.toList(), clock())

    override suspend fun names(): List<String> = dao.all().map { it.name }

    /**
     * The collection named [clean], ignoring case. Compared here rather than in SQL: SQLite's NOCASE
     * folds only A–Z, so "Hörnli" and "HÖRNLI" would count as two names there.
     */
    private suspend fun find(clean: String): CollectionEntity? =
        dao.all().firstOrNull { it.name.equals(clean, ignoreCase = true) }

    override suspend fun addTo(name: String, routeIds: List<String>) {
        val collection = named(name) ?: return
        add(collection.id, routeIds)
    }
}
