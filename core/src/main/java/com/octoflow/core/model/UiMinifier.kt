package com.octoflow.core.model

/**
 * Creates token-efficient minified representations of UiElements for LLM prompts.
 */
object UiMinifier {

    fun minify(root: UiElement): String {
        val builder = StringBuilder()
        builder.append("UI_STATE:\n")
        appendMinifiedElement(root, builder, 0)
        return builder.toString()
    }

    private fun appendMinifiedElement(
        element: UiElement,
        builder: StringBuilder,
        depth: Int
    ) {
        val isInteractive = element.isClickable || element.isEditable || element.isScrollable
        val hasText = element.text?.isNotBlank() == true
        val hasContentDesc = element.contentDescription?.isNotBlank() == true
        val hasHint = element.hint?.isNotBlank() == true

        if (isInteractive || hasText || hasContentDesc || hasHint || element.children.isNotEmpty()) {
            val indent = "  ".repeat(depth)
            builder.append(indent).append("[${element.id}] ")

            val className = element.className.substringAfterLast(".").substringAfterLast("$")
            builder.append(className)

            val attrs = mutableListOf<String>()
            if (element.isClickable) attrs.add("clickable")
            if (element.isEditable) attrs.add("editable")
            if (element.isScrollable) attrs.add("scrollable")
            if (element.isFocused) attrs.add("focused")
            if (!element.isEnabled) attrs.add("disabled")
            if (!element.isVisible) attrs.add("hidden")

            if (attrs.isNotEmpty()) {
                builder.append(" (").append(attrs.joinToString(", ")).append(")")
            }

            element.text?.let {
                if (it.isNotBlank()) builder.append(" \"").append(truncate(it, 50)).append("\"")
            }
            element.contentDescription?.let {
                if (it.isNotBlank()) builder.append(" desc:\"").append(truncate(it, 50)).append("\"")
            }
            element.hint?.let {
                if (it.isNotBlank()) builder.append(" hint:\"").append(truncate(it, 50)).append("\"")
            }

            builder.append(" @(")
                .append(element.bounds.centerX).append(",")
                .append(element.bounds.centerY).append(")")
                .append("\n")

            element.children.forEach { child ->
                appendMinifiedElement(child, builder, depth + 1)
            }
        }
    }

    private fun truncate(str: String, maxLength: Int): String {
        return if (str.length <= maxLength) str else str.take(maxLength - 3) + "..."
    }
}
