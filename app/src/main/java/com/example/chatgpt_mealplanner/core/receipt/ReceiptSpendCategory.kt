package com.example.chatgpt_mealplanner.core.receipt

enum class ReceiptSpendCategory(val displayName: String) {
    FOOD("Food"),
    DRINKS("Drinks"),
    HOUSEHOLD_CLEANING("Household & Cleaning"),
    PERSONAL_CARE("Toiletries & Personal Care"),
    PET("Pet"),
    BABY("Baby"),
    OTHER("Other"),
    UNCATEGORISED("Uncategorised");

    companion object {
        fun fromName(name: String): ReceiptSpendCategory {
            return entries.firstOrNull { it.name.equals(name, ignoreCase = true) }
                ?: entries.firstOrNull { it.displayName.equals(name, ignoreCase = true) }
                ?: UNCATEGORISED
        }

        /** Words are matched from the start of a word, so "ham" finds HAM and HAMBURGER but not SHAMPOO. */
        private fun wordStarts(vararg words: String) =
            Regex("""\b(?:${words.joinToString("|") { Regex.escape(it) }})""")

        private val foodWords = wordStarts(
            "pizza", "frozen", "ice cream", "wedges", "milk", "cheese", "chicken", "ham", "dips", "meat", "bread", "pasta",
            "rice", "canned", "tin", "crisps", "chocolate", "biscuit", "snack",
            "loaf", "bap", "bagel", "bun", "wrap", "muffin", "croissant", "crumpet", "pitta", "naan", "sandwich", "toast",
            "egg", "butter", "yog", "cream", "bacon", "sausage", "beef", "pork", "lamb", "steak", "mince", "fish", "salmon",
            "tuna", "prawn", "turkey", "banana", "apple", "orange", "grape", "berry", "strawberr", "tomato", "potato", "onion",
            "carrot", "lettuce", "salad", "veg", "cucumber", "mushroom", "beans", "soup", "sauce", "ketchup", "mayo", "flour",
            "sugar", "cereal", "oat", "porridge", "jam", "honey", "nut", "cake", "pies", "pastry", "noodle", "curry", "burger",
            "fries", "chips", "popcorn", "sweets", "mars", "snickers", "kitkat", "cadbury", "haribo", "walkers", "pringles",
            "doritos", "hovis", "warburton", "kingsmill", "heinz", "batchelors", "muller", "arla", "philadelphia", "quorn"
        )
        private val drinkWords = wordStarts(
            "juice", "water", "cola", "soda", "drink", "coffee", "tea", "energy",
            "lucozade", "coke", "pepsi", "fanta", "sprite", "irn bru", "red bull", "monster", "relentless", "rockstar", "lemonade",
            "squash", "cordial", "tonic", "dr pepper", "7up", "oasis", "ribena", "robinsons", "tango", "vimto", "capri", "innocent",
            "smoothie", "latte", "cappuccino", "nescafe", "kombucha",
            "wine", "beer", "lager", "cider", "vodka", "whisky", "whiskey", "prosecco", "brandy", "loko", "smirnoff"
        )
        /** Short words that would otherwise match inside others (ALE in ALERT, GIN in GINGER). */
        private val shortDrinkWords = Regex("""\b(?:gin|rum|ale)\b""")
        private val cleaningWords = wordStarts(
            "cleaner", "cleaning", "bleach", "detergent", "washing liquid", "washing powder", "dishwasher", "toilet roll",
            "kitchen roll", "finish", "paper", "bin bag", "foil", "cling film", "sponge", "fairy", "domestos", "persil", "ariel", "surf",
            "battery", "batteries", "light bulb"
        )
        private val personalCareWords = wordStarts(
            "shampoo", "soap", "deodorant", "toothpaste", "toiletries", "toothbrush", "mouthwash", "razor", "conditioner", "shower",
            "body wash", "tampon", "sanitary", "tissue", "dove", "colgate", "listerine", "lynx", "nivea", "vaseline", "plaster", "dettol"
        )
        private val petWords = wordStarts("pet", "cat", "dog", "pedigree", "whiskas", "felix", "dreamies")
        private val babyWords = wordStarts("baby", "nappy", "nappies", "formula", "pampers", "huggies")

        private fun isDrink(lower: String) = drinkWords.containsMatchIn(lower) || shortDrinkWords.containsMatchIn(lower)

        fun classifyDescription(normDesc: String): ReceiptSpendCategory {
            val lower = normDesc.lowercase()
            return when {
                isDrink(lower) && !foodWords.containsMatchIn(lower) -> DRINKS
                foodWords.containsMatchIn(lower) -> FOOD
                isDrink(lower) -> DRINKS
                cleaningWords.containsMatchIn(lower) -> HOUSEHOLD_CLEANING
                personalCareWords.containsMatchIn(lower) -> PERSONAL_CARE
                petWords.containsMatchIn(lower) -> PET
                babyWords.containsMatchIn(lower) -> BABY
                else -> UNCATEGORISED
            }
        }
    }
}
