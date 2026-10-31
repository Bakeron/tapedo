package io.github.bakeron.tapedo.core.rules

import io.github.bakeron.tapedo.core.model.ShoppingGroup

/**
 * Guesses the shop section of a shopping item from word beginnings (Polish and English),
 * so "mleko", "mleka" and "milk" all land in dairy without a dictionary of every word form.
 *
 * Longer stems are checked first, so "serwetki" (household) wins over "ser" (dairy)
 * and "winogrona" (produce) over "wino" (drinks). Stems with a space match anywhere in the item.
 */
internal object ShoppingGroups {

    private val STEMS: Map<ShoppingGroup, List<String>> = mapOf(
        ShoppingGroup.PRODUCE to listOf(
            "pomidor", "ogór", "ogor", "ziemniak", "marchew", "marchewk", "cebul", "czosn", "sałat", "salat",
            "papryk", "jabłk", "jablk", "banan", "cytryn", "pomarańcz", "pomarancz", "grusz", "truskaw",
            "winogron", "owoc", "warzyw", "kapust", "brokuł", "brokul", "pietrusz", "koperek", "szpinak",
            "awokado", "kalafior", "pieczark", "grzyb", "śliw", "sliw", "malin", "borówk", "borowk",
            "tomato", "cucumber", "potato", "carrot", "onion", "garlic", "lettuce", "apple", "banana",
            "lemon", "orange", "pear", "strawberr", "grape", "fruit", "vegetable", "cabbage", "broccoli",
            "spinach", "avocado", "mushroom",
        ),
        ShoppingGroup.BAKERY to listOf(
            "chleb", "chleba", "bułk", "bulk", "bułecz", "bagiet", "rogal", "pieczyw", "drożdżówk",
            "drozdzowk", "chałk", "tortill",
            "bread", "roll", "bun", "baguette", "croissant", "bagel",
        ),
        ShoppingGroup.DAIRY to listOf(
            "mlek", "mleczk", "ser", "serek", "jogurt", "masł", "masl", "śmietan", "smietan", "kefir",
            "twaróg", "twarog", "twarożek", "maślank", "jaj", "jajk",
            "milk", "cheese", "yogurt", "yoghurt", "butter", "cream", "egg",
        ),
        ShoppingGroup.MEAT to listOf(
            "mięs", "mies", "kurczak", "kurczę", "szynk", "kiełbas", "kielbas", "boczek", "boczku",
            "wołow", "wolow", "wieprz", "ryb", "łosoś", "losos", "parówk", "parowk", "filet", "indyk",
            "schab", "mielon", "dorsz", "tuńczyk", "tunczyk",
            "meat", "chicken", "ham", "sausage", "bacon", "beef", "pork", "fish", "salmon", "turkey", "tuna",
        ),
        ShoppingGroup.FROZEN to listOf(
            "mrożon", "mrozon", "lody", "lodów", "frozen", "ice cream",
        ),
        ShoppingGroup.PANTRY to listOf(
            "mąk", "cukier", "cukru", "sól", "soli", "ryż", "ryz", "makaron", "kasz", "olej", "ocet",
            "płatk", "platk", "musli", "dżem", "dzem", "miód", "miod", "przypraw", "pieprz", "konserw",
            "fasol", "groch", "ciastk", "czekolad", "orzech",
            "flour", "sugar", "salt", "rice", "pasta", "noodle", "oil", "vinegar", "cereal", "oats", "jam",
            "honey", "spice", "chocolate", "cookie", "biscuit", "nuts", "beans",
        ),
        ShoppingGroup.DRINKS to listOf(
            "wod", "sok", "kaw", "herbat", "piw", "win", "napój", "napoj", "napoje", "cola", "lemoniad",
            "water", "juice", "coffee", "tea", "beer", "wine", "soda", "lemonade",
        ),
        ShoppingGroup.HOUSEHOLD to listOf(
            "papier", "płyn", "plyn", "proszek", "proszku", "mydł", "mydl", "szampon", "pasta do zęb",
            "pasta do zeb", "gąbk", "gabk", "worki", "worków", "chusteczk", "serwetk", "ręcznik", "recznik",
            "zmywark", "tabletki do", "folia", "folię", "żarówk", "zarowk", "bateri",
            "soap", "shampoo", "detergent", "toothpaste", "toilet paper", "paper towel", "tissue", "sponge",
            "bin bag", "napkin", "battery", "batteries", "light bulb",
        ),
    )

    // Flattened and sorted longest-first, once.
    private val ORDERED: List<Pair<String, ShoppingGroup>> = STEMS
        .flatMap { (group, stems) -> stems.map { it to group } }
        .sortedByDescending { it.first.length }

    private val WORD_SPLIT = Regex("[^\\p{L}]+")

    fun groupOf(item: String): ShoppingGroup {
        val lower = item.lowercase()
        val words = lower.split(WORD_SPLIT).filter { it.isNotEmpty() }
        for ((stem, group) in ORDERED) {
            val hit = if (' ' in stem) stem in lower else words.any { it.startsWith(stem) }
            if (hit) return group
        }
        return ShoppingGroup.OTHER
    }
}
