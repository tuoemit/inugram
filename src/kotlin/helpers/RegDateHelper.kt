package desu.inugram.helpers

import org.json.JSONArray
import org.telegram.messenger.ApplicationLoader
import org.telegram.messenger.FileLog
import org.telegram.messenger.LocaleController
import java.util.Date

object RegDateHelper {
    private const val JSON_FILE = "id_date.json"

    private data class Entry(val id: Long, val date: Long)

    private val entries: List<Entry> by lazy { loadData() }

    private fun loadData(): List<Entry> {
        return try {
            val json = ApplicationLoader.applicationContext.assets.open(JSON_FILE)
                .bufferedReader().use { it.readText() }
            val data = JSONArray(json)
            List(data.length()) { i ->
                val pair = data.getJSONArray(i)
                Entry(pair.getLong(0), pair.getLong(1))
            }
        } catch (e: Exception) {
            FileLog.e(e)
            emptyList()
        }
    }

    private fun formatDate(prefix: String, dateMs: Long): String {
        val str = LocaleController.getInstance().formatterYear.format(Date(dateMs))
        return "$prefix$str"
    }

    @JvmStatic
    fun getRegDate(userId: Long): String? {
        val list = entries
        if (list.isEmpty()) return null
        for (i in 1 until list.size) {
            val a = list[i - 1]
            val b = list[i]
            if (userId in a.id..b.id) {
                val t = (userId - a.id).toDouble() / (b.id - a.id)
                val date = (a.date + t * (b.date - a.date)) * 1000.0
                return formatDate("~", Math.round(date))
            }
        }
        if (userId <= list.first().id) return formatDate("", list.first().date * 1000L)
        return formatDate(">", list.last().date * 1000L)
    }
}
