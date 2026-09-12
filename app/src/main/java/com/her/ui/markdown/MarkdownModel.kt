package com.her.ui.markdown

internal sealed class MdBlock {
    data class Paragraph(val inlines: List<MdInline>) : MdBlock()
    data class Heading(val level: Int, val inlines: List<MdInline>) : MdBlock()
    data class Quote(val inlines: List<MdInline>) : MdBlock()
    data class Code(val text: String) : MdBlock()
    data class ListBlock(val ordered: Boolean, val items: List<List<MdInline>>) : MdBlock()
}

internal sealed class MdInline {
    data class Text(val value: String) : MdInline()
    data class Strong(val children: List<MdInline>) : MdInline()
    data class Emphasis(val children: List<MdInline>) : MdInline()
    data class Strike(val children: List<MdInline>) : MdInline()
    data class Code(val value: String) : MdInline()
    data class Link(val label: List<MdInline>, val url: String) : MdInline()
}

internal object MarkdownParser {
    private val heading = Regex("""^(#{1,6})\s+(.*)$""")
    private val ul = Regex("""^\s*[-*+]\s+(.*)$""")
    private val ol = Regex("""^\s*\d+[.)]\s+(.*)$""")

    fun parse(source: String): List<MdBlock> {
        val lines = source.replace("\r\n", "\n").split('\n')
        val blocks = mutableListOf<MdBlock>()
        var i = 0
        while (i < lines.size) {
            val line = lines[i]
            when {
                line.isBlank() -> i += 1
                line.trimStart().startsWith("```") -> {
                    val body = StringBuilder()
                    i += 1
                    while (i < lines.size && !lines[i].trimStart().startsWith("```")) {
                        if (body.isNotEmpty()) body.append('\n')
                        body.append(lines[i])
                        i += 1
                    }
                    if (i < lines.size) i += 1
                    blocks += MdBlock.Code(body.toString())
                }
                heading.matches(line) -> {
                    val match = heading.matchEntire(line)!!
                    blocks += MdBlock.Heading(match.groupValues[1].length, parseInline(match.groupValues[2]))
                    i += 1
                }
                line.trimStart().startsWith("> ") || line.trimStart() == ">" -> {
                    val quoted = mutableListOf<String>()
                    while (i < lines.size && (lines[i].trimStart().startsWith(">") || lines[i].isBlank() && quoted.isNotEmpty())) {
                        if (lines[i].isBlank()) break
                        quoted += lines[i].trimStart().removePrefix(">").trimStart()
                        i += 1
                    }
                    blocks += MdBlock.Quote(parseInline(quoted.joinToString("\n")))
                }
                ul.matches(line) || ol.matches(line) -> {
                    val ordered = ol.matches(line)
                    val items = mutableListOf<List<MdInline>>()
                    while (i < lines.size) {
                        val item = (if (ordered) ol else ul).matchEntire(lines[i]) ?: break
                        items += parseInline(item.groupValues[1])
                        i += 1
                    }
                    blocks += MdBlock.ListBlock(ordered, items)
                }
                else -> {
                    val para = mutableListOf<String>()
                    while (i < lines.size && lines[i].isNotBlank() &&
                        !lines[i].trimStart().startsWith("```") &&
                        !heading.matches(lines[i]) &&
                        !ul.matches(lines[i]) &&
                        !ol.matches(lines[i]) &&
                        !lines[i].trimStart().startsWith("> ")
                    ) {
                        para += lines[i]
                        i += 1
                    }
                    blocks += MdBlock.Paragraph(parseInline(para.joinToString("\n")))
                }
            }
        }
        return blocks
    }

    fun parseInline(source: String): List<MdInline> {
        val out = mutableListOf<MdInline>()
        var i = 0
        val text = StringBuilder()
        fun flush() {
            if (text.isNotEmpty()) {
                out += MdInline.Text(text.toString())
                text.clear()
            }
        }
        while (i < source.length) {
            when {
                source.startsWith("`", i) && !source.startsWith("```", i) -> {
                    val end = source.indexOf('`', i + 1)
                    if (end < 0) {
                        text.append(source[i])
                        i += 1
                    } else {
                        flush()
                        out += MdInline.Code(source.substring(i + 1, end))
                        i = end + 1
                    }
                }
                source.startsWith("***", i) || source.startsWith("___", i) -> {
                    val delim = source.substring(i, i + 3)
                    val end = source.indexOf(delim, i + 3)
                    if (end < 0) {
                        text.append(source[i])
                        i += 1
                    } else {
                        flush()
                        out += MdInline.Strong(listOf(MdInline.Emphasis(parseInline(source.substring(i + 3, end)))))
                        i = end + 3
                    }
                }
                source.startsWith("**", i) || source.startsWith("__", i) -> {
                    val delim = source.substring(i, i + 2)
                    val end = source.indexOf(delim, i + 2)
                    if (end < 0) {
                        text.append(source[i])
                        i += 1
                    } else {
                        flush()
                        out += MdInline.Strong(parseInline(source.substring(i + 2, end)))
                        i = end + 2
                    }
                }
                source.startsWith("~~", i) -> {
                    val end = source.indexOf("~~", i + 2)
                    if (end < 0) {
                        text.append(source[i])
                        i += 1
                    } else {
                        flush()
                        out += MdInline.Strike(parseInline(source.substring(i + 2, end)))
                        i = end + 2
                    }
                }
                (source[i] == '*' || source[i] == '_') &&
                    (i == 0 || !source[i - 1].isLetterOrDigit()) -> {
                    val delim = source[i]
                    val end = source.indexOf(delim, i + 1)
                    if (end < 0 || (end + 1 < source.length && source[end + 1].isLetterOrDigit())) {
                        text.append(source[i])
                        i += 1
                    } else {
                        flush()
                        out += MdInline.Emphasis(parseInline(source.substring(i + 1, end)))
                        i = end + 1
                    }
                }
                source[i] == '[' -> {
                    val labelEnd = source.indexOf(']', i + 1)
                    val urlStart = if (labelEnd >= 0 && labelEnd + 1 < source.length && source[labelEnd + 1] == '(') {
                        labelEnd + 2
                    } else {
                        -1
                    }
                    val urlEnd = if (urlStart >= 0) source.indexOf(')', urlStart) else -1
                    if (labelEnd < 0 || urlEnd < 0) {
                        text.append(source[i])
                        i += 1
                    } else {
                        flush()
                        out += MdInline.Link(
                            parseInline(source.substring(i + 1, labelEnd)),
                            source.substring(urlStart, urlEnd),
                        )
                        i = urlEnd + 1
                    }
                }
                else -> {
                    text.append(source[i])
                    i += 1
                }
            }
        }
        flush()
        return out
    }
}

internal enum class ContentDirection { Ltr, Rtl }

internal fun List<MdInline>.plainText(): String = buildString {
    this@plainText.forEach { appendPlain(it) }
}

private fun StringBuilder.appendPlain(inline: MdInline) {
    when (inline) {
        is MdInline.Text -> append(inline.value)
        is MdInline.Code -> append(inline.value)
        is MdInline.Strong -> inline.children.forEach { appendPlain(it) }
        is MdInline.Emphasis -> inline.children.forEach { appendPlain(it) }
        is MdInline.Strike -> inline.children.forEach { appendPlain(it) }
        is MdInline.Link -> inline.label.forEach { appendPlain(it) }
    }
}

/** First strong character, same rule as HTML `dir=auto` (Unicode P2: L, R, or AL). */
internal fun firstStrongDirection(text: String): ContentDirection? {
    var i = 0
    while (i < text.length) {
        val cp = text.codePointAt(i)
        i += Character.charCount(cp)
        when (Character.getDirectionality(cp)) {
            Character.DIRECTIONALITY_RIGHT_TO_LEFT,
            Character.DIRECTIONALITY_RIGHT_TO_LEFT_ARABIC,
            -> return ContentDirection.Rtl
            Character.DIRECTIONALITY_LEFT_TO_RIGHT,
            -> return ContentDirection.Ltr
        }
    }
    return null
}
