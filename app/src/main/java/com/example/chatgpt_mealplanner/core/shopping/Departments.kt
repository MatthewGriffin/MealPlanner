package com.example.chatgpt_mealplanner.core.shopping

/**
 * The part of a supermarket an item is found in, worked out from its name, so items the household has never ticked
 * in a shop can still be placed near the rest of their department. The default order is a typical UK supermarket walk.
 */
enum class Department(val displayName: String) {
    PRODUCE("Fruit & veg"),
    BAKERY("Bakery"),
    MEAT_FISH("Meat & fish"),
    DAIRY_EGGS("Dairy & eggs"),
    CHILLED("Chilled & ready meals"),
    CUPBOARD("Cupboard & tins"),
    SAUCES_SPICES("Sauces, herbs & spices"),
    SNACKS("Snacks & sweets"),
    DRINKS("Drinks"),
    FROZEN("Frozen"),
    HOUSEHOLD("Household & toiletries"),
    OTHER("Other");

    companion object {
        private fun words(vararg w: String) = Regex("""\b(?:${w.joinToString("|") { Regex.escape(it) }})""")

        // Checked in this order: the first department whose words match wins, so specific kinds come first.
        private val rules: List<Pair<Department, Regex>> = listOf(
            FROZEN to words("frozen", "ice cream", "fish fingers", "oven chips", "waffles", "sorbet"),
            // Tinned and jarred versions of fresh things live in the cupboard aisles.
            CUPBOARD to words("tinned", "canned", "baked beans", "chopped tomato", "peanut butter", "coconut milk", "almond milk", "oat milk", "soya milk"),
            HOUSEHOLD to words(
                "washing", "detergent", "bleach", "toilet", "kitchen roll", "bin bag", "foil", "cling film", "sponge", "shampoo",
                "soap", "toothpaste", "deodorant", "razor", "tissue", "nappy", "nappies", "dishwasher", "cleaner", "battery", "batteries", "light bulb"
            ),
            DRINKS to words(
                "juice", "water", "cola", "coke", "pepsi", "lemonade", "squash", "cordial", "coffee", "tea", "wine", "beer", "lager",
                "cider", "gin", "vodka", "whisky", "prosecco", "smoothie", "tonic", "energy drink", "fizzy"
            ),
            BAKERY to words("bread", "loaf", "bap", "bagel", "buns", "roll", "wrap", "muffin", "croissant", "crumpet", "pitta", "naan", "baguette", "brioche", "tortilla", "cake", "pastry"),
            MEAT_FISH to words(
                "chicken", "beef", "pork", "lamb", "turkey", "duck", "bacon", "sausage", "ham", "mince", "steak", "gammon", "drumstick",
                "thigh", "breast", "fish", "salmon", "cod", "haddock", "tuna", "prawn", "shrimp", "mackerel", "sardine", "chorizo", "salami"
            ),
            DAIRY_EGGS to words("milk", "cheese", "butter", "cream", "yogurt", "yoghurt", "yog", "egg", "margarine", "cheddar", "mozzarella", "feta", "parmesan", "halloumi", "creme"),
            PRODUCE to words(
                "apple", "banana", "orange", "lemon", "lime", "grape", "berry", "berries", "strawberr", "raspberr", "blueberr", "melon", "mango", "pear",
                "peach", "plum", "avocado", "tomato", "potato", "onion", "garlic", "ginger", "carrot", "lettuce", "salad", "spinach", "kale",
                "cucumber", "pepper", "chilli", "chili", "jalapeno", "courgette", "aubergine", "mushroom", "broccoli", "cauliflower", "cabbage",
                "celery", "leek", "spring onion", "sweetcorn", "pea", "bean sprout", "coriander", "parsley", "basil", "mint", "thyme", "rosemary", "herb",
                "veg", "fruit", "squash butternut", "pumpkin", "beetroot", "radish", "asparagus"
            ),
            CHILLED to words("pizza", "ready meal", "lasagne", "quiche", "pie", "sandwich", "hummus", "dip", "fresh pasta", "coleslaw", "soup fresh"),
            SNACKS to words("crisps", "nacho", "chocolate", "biscuit", "sweets", "snack", "popcorn", "nuts", "cracker", "pringles", "haribo", "chocolate bar"),
            SAUCES_SPICES to words(
                "sauce", "ketchup", "mayo", "mustard", "vinegar", "oil", "stock", "gravy", "paste", "pesto", "salt", "pepper corn", "spice", "paprika",
                "cumin", "turmeric", "cinnamon", "oregano", "seasoning", "curry", "soy", "dressing", "relish", "pickle", "jalapeno slices"
            ),
            CUPBOARD to words(
                "pasta", "rice", "noodle", "flour", "sugar", "cereal", "oats", "porridge", "jam", "honey", "peanut butter", "tin", "tinned", "canned",
                "beans", "chickpea", "lentil", "tomatoes tinned", "soup", "coconut milk", "couscous", "baking", "yeast", "cocoa", "syrup"
            )
        )

        fun classify(displayName: String): Department {
            // Accents are dropped so "jalapeños" is "jalapenos".
            val lower = java.text.Normalizer.normalize(displayName, java.text.Normalizer.Form.NFD).replace(Regex("\\p{M}+"), "").lowercase()
            return rules.firstOrNull { (_, regex) -> regex.containsMatchIn(lower) }?.first ?: OTHER
        }
    }
}
