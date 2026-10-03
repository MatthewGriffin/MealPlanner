package com.example.chatgpt_mealplanner.feature.chat.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle

@Composable
fun AssistantMarkdownText(
    markdown: String,
    modifier: Modifier = Modifier
) {
    val annotatedString = parseMarkdownToAnnotatedString(markdown)
    Text(
        text = annotatedString,
        style = MaterialTheme.typography.bodyMedium,
        modifier = modifier
    )
}

fun parseMarkdownToAnnotatedString(markdown: String): AnnotatedString {
    if (markdown.isBlank()) return AnnotatedString("")

    return try {
        buildAnnotatedString {
            val lines = markdown.split("\n")
            lines.forEachIndexed { index, line ->
                val trimmed = line.trim()
                when {
                    trimmed.startsWith("### ") -> {
                        withStyle(SpanStyle(fontWeight = FontWeight.Bold)) {
                            appendFormattedInline(trimmed.removePrefix("### "))
                        }
                    }
                    trimmed.startsWith("## ") -> {
                        withStyle(SpanStyle(fontWeight = FontWeight.Bold)) {
                            appendFormattedInline(trimmed.removePrefix("## "))
                        }
                    }
                    trimmed.startsWith("# ") -> {
                        withStyle(SpanStyle(fontWeight = FontWeight.Bold)) {
                            appendFormattedInline(trimmed.removePrefix("# "))
                        }
                    }
                    trimmed.startsWith("• ") || trimmed.startsWith("- ") || trimmed.startsWith("* ") || trimmed.startsWith("+ ") -> {
                        append("• ")
                        val content = when {
                            trimmed.startsWith("• ") -> trimmed.removePrefix("• ")
                            trimmed.startsWith("- ") -> trimmed.removePrefix("- ")
                            trimmed.startsWith("+ ") -> trimmed.removePrefix("+ ")
                            else -> trimmed.removePrefix("* ")
                        }
                        appendFormattedInline(content)
                    }
                    isNumberedListLine(trimmed) -> {
                        val dotIndex = trimmed.indexOf(". ")
                        val prefix = trimmed.substring(0, dotIndex + 2)
                        val content = trimmed.substring(dotIndex + 2)
                        withStyle(SpanStyle(fontWeight = FontWeight.Bold)) {
                            append(prefix)
                        }
                        appendFormattedInline(content)
                    }
                    else -> {
                        appendFormattedInline(line)
                    }
                }
                if (index < lines.size - 1) {
                    append("\n")
                }
            }
        }
    } catch (_: Exception) {
        AnnotatedString(markdown)
    }
}

private fun isNumberedListLine(line: String): Boolean {
    val dotIndex = line.indexOf(". ")
    if (dotIndex <= 0 || dotIndex > 4) return false
    return line.substring(0, dotIndex).all { it.isDigit() }
}

private fun AnnotatedString.Builder.appendFormattedInline(inlineText: String) {
    var idx = 0
    val length = inlineText.length

    while (idx < length) {
        val nextBold = findNextDelimiter(inlineText, idx, "**")
        val nextItalic = findNextSingleStar(inlineText, idx)
        val nextCode = findNextDelimiter(inlineText, idx, "`")

        val minOffset = listOf(nextBold, nextItalic, nextCode).filter { it != -1 }.minOrNull() ?: -1

        if (minOffset == -1) {
            append(inlineText.substring(idx))
            break
        }

        if (minOffset > idx) {
            append(inlineText.substring(idx, minOffset))
            idx = minOffset
        }

        when {
            minOffset == nextBold -> {
                val closing = inlineText.indexOf("**", idx + 2)
                if (closing != -1) {
                    val boldContent = inlineText.substring(idx + 2, closing)
                    withStyle(SpanStyle(fontWeight = FontWeight.Bold)) {
                        append(boldContent)
                    }
                    idx = closing + 2
                } else {
                    append(inlineText.substring(idx))
                    break
                }
            }
            minOffset == nextCode -> {
                val closing = inlineText.indexOf("`", idx + 1)
                if (closing != -1) {
                    val codeContent = inlineText.substring(idx + 1, closing)
                    withStyle(SpanStyle(fontFamily = FontFamily.Monospace)) {
                        append(codeContent)
                    }
                    idx = closing + 1
                } else {
                    append(inlineText.substring(idx))
                    break
                }
            }
            minOffset == nextItalic -> {
                val closing = inlineText.indexOf("*", idx + 1)
                if (closing != -1 && (closing + 1 >= length || inlineText[closing + 1] != '*')) {
                    val italicContent = inlineText.substring(idx + 1, closing)
                    withStyle(SpanStyle(fontStyle = FontStyle.Italic)) {
                        append(italicContent)
                    }
                    idx = closing + 1
                } else {
                    append(inlineText.substring(idx))
                    break
                }
            }
            else -> {
                append(inlineText[idx])
                idx++
            }
        }
    }
}

private fun findNextDelimiter(text: String, start: Int, delimiter: String): Int {
    return text.indexOf(delimiter, start)
}

private fun findNextSingleStar(text: String, start: Int): Int {
    var i = start
    while (i < text.length) {
        if (text[i] == '*') {
            val isDouble = (i + 1 < text.length && text[i + 1] == '*') || (i - 1 >= 0 && text[i - 1] == '*')
            if (!isDouble) return i
        }
        i++
    }
    return -1
}
