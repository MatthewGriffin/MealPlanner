package com.example.chatgpt_mealplanner.core.ai.packet

/**
 * Cuts the text a phone reads off a whole packet down to the cooking instructions. A packet also carries
 * a nutrition table, ingredients, allergy advice, storage and recycling notes, a company address, contact
 * details and barcodes, none of which Claude needs to cook the kit.
 *
 * Works line by line. A heading such as "Nutrition" or "Ingredients" starts a block that is dropped until
 * an instruction heading or a line that starts like a cooking step; single lines that are plainly an
 * address, contact detail, barcode or nutrition figure are dropped wherever they are.
 */
object PacketTextFilter {

    private val noiseHeading = Regex(
        """^\W*(nutrition(al)?( information| values?| facts)?|typical values|ingredients?|allergy( advice)?|allergens?|storage|store |keep |recycl|packed|manufactured|produced|distributed|imported|contact|customer|careline|returns|reference intake|per \d+\s?g|per serving|per portion)\b""",
        RegexOption.IGNORE_CASE
    )
    private val instructionHeading = Regex(
        """^\W*(instructions?|method|directions?|cooking( instructions| guide)?|preparation|how to (cook|prepare|make)|you will need|you'll need|to cook|to prepare|step\s*\d+|what you need|oven|hob|microwave)\b""",
        RegexOption.IGNORE_CASE
    )
    private val stepStart = Regex(
        """^\W*(\d+[.)]|step\s*\d+|add|heat|stir|cook|fry|simmer|mix|bake|boil|pour|serve|place|put|preheat|pre-heat|warm|season|spoon|sprinkle|combine|bring|reduce|remove|cut|slice|drain|rinse|leave|rest|spread|fill|roll|empty|open|tip|cover|toss)\b""",
        RegexOption.IGNORE_CASE
    )
    private val noiseLine = Regex(
        """\b(kcal|kj|saturates?|carbohydrates?|of which|reference intake|www\.|https?:|@|\.com\b|\.co\.uk\b|ltd\.?|limited|plc|freephone|careline|tel[:.]|customer (care|service)|recycl|best before|use by|batch|lot no|made in|produced in|packed in|manufactured|distributed by|registered (office|trade)|trademark|©|®|™)""",
        RegexOption.IGNORE_CASE
    )
    private val postcode = Regex("""\b[A-Z]{1,2}\d[A-Z\d]?\s*\d[A-Z]{2}\b""")
    private val digitsOnly = Regex("""^[\d\s\-–/.,%]+$""")
    private val figure = Regex("""^\W*(energy|fat|salt|sugars?|protein|fibre|fiber)\b.*\d""", RegexOption.IGNORE_CASE)

    /** The instructions part of [fullText]; the original text if nothing would be left. */
    fun keepInstructions(fullText: String): String {
        val kept = mutableListOf<String>()
        var skipping = false
        for (raw in fullText.lines()) {
            val line = raw.trim()
            if (line.isEmpty()) continue
            if (instructionHeading.containsMatchIn(line) && !noiseHeading.containsMatchIn(line)) {
                skipping = false
            } else if (noiseHeading.containsMatchIn(line)) {
                skipping = true
                continue
            } else if (skipping && stepStart.containsMatchIn(line)) {
                skipping = false
            }
            if (skipping) continue
            if (digitsOnly.matches(line) || figure.containsMatchIn(line) || postcode.containsMatchIn(line) || noiseLine.containsMatchIn(line)) continue
            kept += line
        }
        return if (kept.isEmpty()) fullText.trim() else kept.joinToString("\n")
    }
}
