package com.verdenroz.corvid.core.data

import org.jsoup.Jsoup

actual fun flattenHtml(html: String): String = Jsoup.parse(html).text()
