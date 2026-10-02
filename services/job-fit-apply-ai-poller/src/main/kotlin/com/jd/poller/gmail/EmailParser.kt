package com.jd.poller.gmail

import com.google.api.services.gmail.model.Message
import com.google.api.services.gmail.model.MessagePart
import org.jsoup.Jsoup
import org.jsoup.parser.Parser
import org.jsoup.safety.Safelist
import java.nio.charset.StandardCharsets
import java.util.Base64

data class ParsedEmail(
    val plainText: String,
    val htmlBodies: List<String>,
)

/** Pure MIME decoding of a Gmail API [Message]. No auth, no network. */
object EmailParser {

    fun parse(msg: Message): ParsedEmail {
        val payload = msg.payload
        val plainText = decodeBody(payload)
        val htmlBodies = mutableListOf<String>()
        collectHtmlBodies(payload, htmlBodies)

        return ParsedEmail(
            plainText = plainText,
            htmlBodies = htmlBodies,
        )
    }

    private fun decodeBody(payload: MessagePart?): String {
        if (payload == null) return ""

        val mimeType = payload.mimeType

        if (mimeType == "text/plain") {
            if (payload.body != null && payload.body.data != null) {
                val bytes = Base64.getUrlDecoder().decode(payload.body.data)
                return String(bytes, StandardCharsets.UTF_8)
            }
        }

        if (mimeType == "text/html") {
            if (payload.body != null && payload.body.data != null) {
                val bytes = Base64.getUrlDecoder().decode(payload.body.data)
                val html = String(bytes, StandardCharsets.UTF_8)
                val cleanedHtml = html.replace(Regex("<a\\b[^>]*\\bhref=[\"']([^\"']+)[\"'][^>]*>(.*?)</a>"), "$2 $1")
                // Jsoup.clean returns HTML: its text is entity-escaped (&amp;, &lt;, &nbsp;).
                // Decode it so an HTML-only email reads like its text/plain twin would.
                val text = Parser.unescapeEntities(Jsoup.clean(cleanedHtml, Safelist.none()), false)
                return text.replace(Regex("[\\s\u00A0]+"), " ").trim()
            }
        }

        if (mimeType == "multipart/alternative" || mimeType == "multipart/mixed" || mimeType == "multipart/related") {
            if (payload.parts != null) {
                for (part in payload.parts) {
                    if (part.mimeType == "text/plain") {
                        val body = decodeBody(part)
                        if (body.isNotEmpty()) return body
                    }
                }
                for (part in payload.parts) {
                    val body = decodeBody(part)
                    if (body.isNotEmpty()) return body
                }
            }
        }

        return ""
    }

    private fun collectHtmlBodies(part: MessagePart?, htmlBodies: MutableList<String>) {
        if (part == null) return

        if (part.mimeType == "text/html" && part.body?.data != null) {
            htmlBodies.add(decodePartData(part.body.data))
        }

        part.parts?.forEach { child ->
            collectHtmlBodies(child, htmlBodies)
        }
    }

    private fun decodePartData(data: String): String {
        val bytes = Base64.getUrlDecoder().decode(data)
        return String(bytes, StandardCharsets.UTF_8)
    }
}
