package it.casate.scanner

import kotlin.math.ceil
import kotlin.math.roundToInt

/**
 * The catalog published by the purchasing program (CASA&TE 采购系统) and what a scan means for the store.
 * Pure Kotlin (no Android classes) so it can be unit-tested.
 */
data class Item(
    val ean: String,
    val name: String,
    /** 0 = not in the DETERSIVO Griglia, 1 = in the Griglia, 2 = Griglia, discontinued */
    val griglia: Int,
    /** Wholesalers that list it this week (names). */
    val wholesalers: List<String>,
    val substituteEan: String,
    val substituteName: String,
    /** Direct supplier (SCHEDA) that sells it, or "". */
    val direct: String,
    /** Pieces per box (Griglia / wholesaler list), cheapest wholesaler piece price this week and who. */
    val pack: Double? = null,
    val bestPrice: Double? = null,
    val bestSource: String = "",
)

/** This store's numbers for an item (from the purchasing program; any of them may be missing). */
data class StoreInfo(
    val stock: Double? = null,
    val sold30: Double? = null,
    val transitBoxes: Double? = null,
    val suggestBoxes: Int? = null,
    val cost: Double? = null,
    val price: Double? = null,
    val promoPrice: Double? = null,
    val promoEnd: String = "",
    val pack: Double? = null,
    /** Days since the last sale (null = unknown / no sale this year). */
    val lastSaleDays: Int? = null,
    /** The purchasing program thinks the stock may not be real (it should sell, but nothing sold for a long time). */
    val suspect: Boolean = false,
) {
    /** Days the stock lasts at the last 30 days' sales (null = no sales). */
    fun coverDays(extraPieces: Double = 0.0): Int? {
        val s = sold30 ?: return null
        if (s <= 0) return null
        return (((stock ?: 0.0).coerceAtLeast(0.0) + extraPieces) / (s / 30.0)).roundToInt()
    }
}

enum class Kind {
    /** In Griglia, a wholesaler has it this week. */
    ORDER,
    /** Replaced: order the substitute instead. */
    SUBSTITUTE,
    /** In Griglia but no wholesaler has it this week. */
    NOT_AVAILABLE,
    /** Not in Griglia (or taken out) but a wholesaler has it: only for emergencies, normally the direct supplier. */
    URGENT_ONLY,
    /** Not in Griglia, no wholesaler: only from the direct supplier (if any). */
    DIRECT_ONLY,
    /** Not in the catalog at all. */
    UNKNOWN,
}

data class Verdict(val kind: Kind, val item: Item?, val substitute: Item?)

class Catalog(
    val id: Int,
    val week: String,
    val publishedAt: String,
    private val items: Map<String, Item>,
    private val store: Map<String, StoreInfo> = emptyMap(),
    val imageUrl: String = "",
) {
    val size get() = items.size
    val hasStoreData get() = store.isNotEmpty()

    fun find(scan: String): Item? {
        for (k in keys(scan)) items[k]?.let { return it }
        return null
    }

    fun info(ean: String): StoreInfo? {
        for (k in keys(ean)) store[k]?.let { return it }
        return null
    }

    fun judge(scan: String): Verdict = judgeItem(find(scan)) { find(it) }

    /** Items whose name has all the words (or whose barcode contains the digits): Griglia first. */
    fun search(q: String, limit: Int = 30): List<Item> {
        val t = q.trim()
        if (t.isEmpty()) return emptyList()
        val digits = t.all { it.isDigit() }
        val words = t.uppercase().split(' ').filter { it.isNotBlank() }
        val key = t.trimStart('0')
        val out = items.values.filter { if (digits) it.ean.contains(key) else it.name.uppercase().let { n -> words.all { w -> n.contains(w) } } }
        return out.sortedWith(compareBy<Item>({ if (it.griglia == 1) 0 else if (it.griglia == 2) 1 else 2 }, { it.name })).take(limit)
    }

    companion object {
        /** Ways the same barcode may be written: as scanned, without leading zeros (how the catalog stores it). */
        fun keys(scan: String): List<String> {
            val digits = scan.trim().filter { it.isDigit() }
            if (digits.isEmpty()) return emptyList()
            val noZeros = digits.trimStart('0')
            return listOf(noZeros, digits).filter { it.isNotEmpty() }.distinct()
        }

        fun judgeItem(item: Item?, lookup: (String) -> Item? = { null }): Verdict {
            if (item == null) return Verdict(Kind.UNKNOWN, null, null)
            val sub = if (item.substituteEan.isNotEmpty() && item.griglia != 0)
                lookup(item.substituteEan) ?: Item(item.substituteEan, item.substituteName, 1, emptyList(), "", "", "")
            else null
            return when {
                item.griglia == 1 && item.wholesalers.isNotEmpty() -> Verdict(Kind.ORDER, item, null)
                sub != null -> Verdict(Kind.SUBSTITUTE, item, sub)
                item.griglia == 1 -> Verdict(Kind.NOT_AVAILABLE, item, null)
                item.wholesalers.isNotEmpty() -> Verdict(Kind.URGENT_ONLY, item, null)
                else -> Verdict(Kind.DIRECT_ONLY, item, null)
            }
        }

        /** Boxes to start with: what is already in the list, else the suggestion, else 1. */
        fun startBoxes(inList: Int?, info: StoreInfo?): Int = when {
            inList != null && inList > 0 -> inList
            (info?.suggestBoxes ?: 0) > 0 -> info!!.suggestBoxes!!
            else -> 1
        }

        /** After ordering `boxes`, how many days the stock lasts (null = unknown). */
        fun coverAfter(info: StoreInfo?, boxes: Int): Int? {
            info ?: return null
            val pack = info.pack ?: return info.coverDays()
            val transit = (info.transitBoxes ?: 0.0) * pack
            return info.coverDays(boxes * pack + transit)
        }

        /** Already well stocked: what is in the shop (and coming) lasts more than `days` without this order. */
        fun overstocked(info: StoreInfo?, days: Int = 90): Boolean {
            info ?: return false
            val transit = (info.transitBoxes ?: 0.0) * (info.pack ?: 0.0)
            val c = info.coverDays(transit) ?: return (info.stock ?: 0.0) > 0 && (info.sold30 ?: -1.0) == 0.0
            return c > days
        }

        fun months(days: Int): String = when {
            days < 45 -> "$days giorni"
            else -> "${ceil(days / 30.0).toInt()} mesi"
        }
    }
}
