package com.verdenroz.corvid.core.ui.provenance

import androidx.compose.ui.platform.UriHandler
import com.verdenroz.corvid.core.model.Provenance

/** The one way a link carried by a message is opened; [Provenance.canOpen] decides. */
fun UriHandler.openUntrusted(
    url: String,
    provenance: Provenance,
) {
    if (provenance.canOpen(url)) openUri(url)
}
