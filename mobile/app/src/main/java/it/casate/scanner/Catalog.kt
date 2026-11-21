package it.casate.scanner

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
)

enum class Tone { OK, SUBSTITUTE, WARN, STOP, UNKNOWN }

data class Verdict(val tone: Tone, val title: String, val lines: List<String>)

class Catalog(
    val id: Int,
    val week: String,
    val publishedAt: String,
    private val items: Map<String, Item>,
) {
    val size get() = items.size

    fun find(scan: String): Item? {
        for (k in keys(scan)) items[k]?.let { return it }
        return null
    }

    companion object {
        /** Ways the same barcode may be written: as scanned, without leading zeros (how the catalog stores it). */
        fun keys(scan: String): List<String> {
            val digits = scan.trim().filter { it.isDigit() }
            if (digits.isEmpty()) return emptyList()
            val noZeros = digits.trimStart('0')
            return listOf(noZeros, digits).filter { it.isNotEmpty() }.distinct()
        }

        fun judge(item: Item?, scan: String): Verdict {
            if (item == null) {
                return Verdict(
                    Tone.UNKNOWN, "NON IN GRIGLIA",
                    listOf("Articolo non presente in Griglia né nei listini dei grossisti.", "Non inserirlo nella lista DETERSIVO."),
                )
            }
            val subst = if (item.substituteEan.isNotEmpty()) item.substituteName.ifEmpty { item.substituteEan } else ""
            return when {
                item.griglia == 1 && item.wholesalers.isNotEmpty() -> Verdict(
                    Tone.OK, "ORDINABILE",
                    listOf("In Griglia · disponibile questa settimana", "Grossisti: " + item.wholesalers.joinToString(", ")),
                )
                item.griglia != 0 && subst.isNotEmpty() -> Verdict(
                    Tone.SUBSTITUTE, "SOSTITUITO",
                    listOf("Articolo fuori listino. Ordinare al suo posto:", subst, "EAN " + item.substituteEan),
                )
                item.griglia == 1 -> Verdict(
                    Tone.WARN, "NON DISPONIBILE",
                    listOf("In Griglia, ma questa settimana nessun grossista lo ha in listino.",
                        if (item.direct.isNotEmpty()) "Ordinabile dal fornitore diretto: " + item.direct else "L'ufficio acquisti cerca un'alternativa."),
                )
                item.griglia == 2 -> Verdict(
                    Tone.STOP, "TOLTO DALLA GRIGLIA",
                    listOf("Articolo non più in Griglia: non ordinarlo nella lista DETERSIVO.") +
                        (if (item.direct.isNotEmpty()) listOf("Ordinabile dal fornitore diretto: " + item.direct) else emptyList()),
                )
                else -> Verdict(
                    Tone.STOP, "NON IN GRIGLIA",
                    listOf("Non inserirlo nella lista DETERSIVO.") +
                        (if (item.direct.isNotEmpty()) listOf("Ordinabile dal fornitore diretto: " + item.direct) else emptyList()) +
                        (if (item.wholesalers.isNotEmpty()) listOf("(nei listini dei grossisti, ma fuori Griglia)") else emptyList()),
                )
            }
        }
    }
}
