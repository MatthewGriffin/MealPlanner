package com.example.chatgpt_mealplanner.ui.text

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withLink

private val webAddress = Regex("""https?://[^\s<>"]+""")

/** Where each web address is in the text, leaving out punctuation that ends a sentence around it. */
fun webAddressRanges(text: String): List<IntRange> = webAddress.findAll(text).map { match ->
    val trimmed = match.value.trimEnd('.', ',', ';', ':', '!', '?', ')', ']', '\'', '"')
    match.range.first until match.range.first + trimmed.length
}.filter { !it.isEmpty() }.toList()

/** The text with every web address tappable, opening in the browser. */
fun withTappableLinks(text: String, linkColor: Color): AnnotatedString = buildAnnotatedString {
    var start = 0
    webAddressRanges(text).forEach { range ->
        append(text.substring(start, range.first))
        val url = text.substring(range)
        withLink(LinkAnnotation.Url(url, TextLinkStyles(SpanStyle(color = linkColor, textDecoration = TextDecoration.Underline)))) {
            append(url)
        }
        start = range.last + 1
    }
    append(text.substring(start))
}
