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

/** The system's suggestion: boxes (null = cannot say), whether it is the app's own estimate, and why in one line. */
data class Advice(val boxes: Int?, val estimated: Boolean, val reason: String)

class Catalog(
    val id: Int,
    val week: String,
    val publishedAt: String,
    private val items: Map<String, Item>,
    private val store: Map<String, StoreInfo> = emptyMap(),
    val imageUrl: String = "",
    /** Days the advice covers (data day → next delivery after the deadline), from the purchasing program. */
    val horizon: Int = 14,
    /** Day the store numbers are from (yyyy-mm-dd), "" = unknown. */
    val dataDate: String = "",
) {
    init { Companion.horizon = horizon.coerceIn(7, 28) }
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
        fun startBoxes(inList: Int?, info: StoreInfo?, pack: Double? = null): Int {
            if (inList != null && inList > 0) return inList
            val a = advise(info, pack).boxes ?: 0
            return if (a > 0) a else 1
        }

        /**
         * What the system suggests for this store (always something to show the person ordering):
         * the purchasing program's number when it computed one (Griglia items and substitutes), else an
         * estimate from the last 30 days (one month of sales − stock − what is coming), else nothing.
         */
        /** Days the estimate covers (set by the last catalog loaded; see the purchasing program's store_advice). */
        @Volatile var horizon: Int = 14

        fun advise(info: StoreInfo?, pack: Double?): Advice {
            info ?: return Advice(null, false, "Dati del negozio non ancora pubblicati")
            val pk = (info.pack ?: pack)?.takeIf { it > 0 }
            val transit = info.transitBoxes ?: 0.0
            val transitPcs = transit * (pk ?: 0.0)
            val stock = (info.stock ?: 0.0).coerceAtLeast(0.0)
            val sold = info.sold30 ?: 0.0
            val cover = info.coverDays(transitPcs)
            val sys = info.suggestBoxes
            val boxes: Int?
            val estimated: Boolean
            if (sys != null) {
                boxes = sys.coerceAtLeast(0); estimated = false
            } else if (pk != null && sold > 0) {
                // until the next delivery (weekly order), not a whole month
                boxes = ceil((sold / 30.0 * horizon - stock - transitPcs) / pk - 1e-9).toInt().coerceAtLeast(0); estimated = true
            } else if (sold <= 0 && stock > 0) {
                boxes = 0; estimated = true
            } else return Advice(null, false, if (pk == null) "Senza pezzi per collo: decidi tu" else "Nessuna vendita negli ultimi 30 giorni")
            val reason = when {
                boxes > 0 && stock <= 0 -> "Scaffale vuoto · vendi ${fmt(sold)} pz al mese"
                boxes > 0 && cover != null -> "Ti bastano ${cover} giorni · vendi ${fmt(sold)} pz al mese"
                boxes > 0 -> "Vendi ${fmt(sold)} pz al mese"
                transit > 0 && cover != null -> "${fmt(transit)} ${if (transit == 1.0) "collo" else "colli"} già in arrivo: bastano ${months(cover)}"
                transit > 0 -> "${fmt(transit)} ${if (transit == 1.0) "collo" else "colli"} già in arrivo"
                cover != null -> "Hai ${fmt(stock)} pz: bastano ${months(cover)}"
                else -> "Hai ${fmt(stock)} pz e non si vende da 30 giorni"
            }
            return Advice(boxes, estimated, reason)
        }

        private fun fmt(v: Double) = if (v == Math.floor(v)) v.toLong().toString() else String.format(java.util.Locale.ITALY, "%.1f", v)

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
