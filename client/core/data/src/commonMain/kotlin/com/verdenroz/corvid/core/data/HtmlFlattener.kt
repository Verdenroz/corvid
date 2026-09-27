package com.verdenroz.corvid.core.data

/** An HTML-only body is flattened to text and never rendered: no WebView, no HTML parser in the UI layer. */
expect fun flattenHtml(html: String): String
