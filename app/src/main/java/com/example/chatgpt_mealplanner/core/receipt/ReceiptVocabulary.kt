package com.example.chatgpt_mealplanner.core.receipt

import java.text.Normalizer

/**
 * The words on a till receipt that are not products (totals, payment, tax, discounts) in the languages receipts are most
 * often printed in. Accents are ignored, and words only match whole. Words that are also products ("BAR", "PIN") are left out.
 */
object ReceiptVocabulary {
    fun fold(text: String): String = Normalizer.normalize(text.lowercase(), Normalizer.Form.NFD).replace(Regex("\\p{M}+"), "")

    private fun words(vararg w: String) =
        Regex("""(?<![\p{L}\p{N}])(?:${w.joinToString("|") { Regex.escape(fold(it)) }})(?![\p{L}\p{N}])""")

    /** A line that gives the amount to pay. */
    private val totalWords = words(
        "total", "totaal", "totale", "totalt", "summe", "gesamt", "gesamtsumme", "gesamtbetrag", "zu zahlen", "a payer", "a pagar",
        "da pagare", "te betalen", "att betala", "razem", "do zaplaty", "celkem", "toplam", "importe", "betrag", "amount due",
        "to pay", "balance due", "net a payer", "total ttc", "total a pagar", "totale complessivo", "totalbetrag"
    )

    /** Paid by card: the line carries the card amount. */
    private val cardWords = words(
        "tendered", "mastercard", "visa", "maestro", "amex", "american express", "debit", "credit", "kreditkarte", "ec-karte",
        "ec karte", "girocard", "bancomat", "carte bancaire", "tarjeta", "contactless", "apple pay", "google pay", "paypal",
        "card payment", "payment approved"
    )

    /** How it was paid and the change: from here the receipt no longer lists products. */
    private val paymentWords = words(
        "bargeld", "especes", "efectivo", "contanti", "contant", "kontant", "rueckgeld", "ruckgeld", "wechselgeld", "monnaie rendue",
        "rendu monnaie", "cambio", "resto", "bezahlt", "betaald", "gegeben", "cash", "change"
    )

    private val taxWords = words("vat", "tax", "gst", "hst", "pst", "mwst", "ust", "tva", "iva", "btw", "moms", "mva", "sales tax")

    private val discountWords = words(
        "discount", "savings", "saving", "rabatt", "ermassigung", "remise", "reduction", "descuento", "sconto", "korting", "rabat",
        "alennus", "coupon", "markdown", "mark down", "promo", "multibuy"
    )

    fun isTotalLine(text: String) = totalWords.containsMatchIn(fold(text))
    fun startsWithTotal(text: String): Boolean = totalWords.find(fold(text).trimStart())?.range?.first == 0
    fun isCardLine(text: String) = cardWords.containsMatchIn(fold(text))
    fun isPaymentLine(text: String) = isCardLine(text) || paymentWords.containsMatchIn(fold(text))
    fun isTaxLine(text: String) = taxWords.containsMatchIn(fold(text))
    fun isDiscountLine(text: String) = discountWords.containsMatchIn(fold(text))

    /** A line that is part of the receipt's summary rather than a product. */
    fun isNonProduct(text: String) = isTotalLine(text) || isPaymentLine(text) || isTaxLine(text)
}
