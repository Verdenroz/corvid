package com.verdenroz.corvid.core.designsystem.component

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.ui.unit.dp

/** Corvid's line icons on a 24-unit grid; `Icon` tints the black stroke. */
object CorvidIcons {
    val Search: ImageVector by lazy { stroke("Search", "M11 18a7 7 0 1 1 0-14 7 7 0 0 1 0 14zM20 20l-4-4") }

    val Pencil: ImageVector by lazy { stroke("Pencil", "M4 20l4-1 11-11-3-3L5 16l-1 4zM14 6l3 3") }

    val Inbox: ImageVector by lazy { stroke("Inbox", "M4 13l2-8h12l2 8M4 13v6h16v-6M4 13h5l1 2h4l1-2h5") }

    val Trash: ImageVector by lazy { stroke("Trash", "M5 7h14M10 11v6M14 11v6M6 7l1 13h10l1-13M9 7V4h6v3") }

    val Archive: ImageVector by lazy { stroke("Archive", "M4 5h16v4H4zM5 9v10h14V9M10 13h4") }

    val Lock: ImageVector by lazy { stroke("Lock", "M7 11V8a5 5 0 0 1 10 0v3M6 11h12v9H6z") }

    val Reply: ImageVector by lazy { stroke("Reply", "M10 7L4 12l6 5M4 12h10a6 6 0 0 1 6 6") }

    val ReplyAll: ImageVector by lazy { stroke("ReplyAll", "M8 7l-5 5 5 5M13 7l-5 5 5 5M8 12h7a6 6 0 0 1 6 6") }

    val Forward: ImageVector by lazy { stroke("Forward", "M14 7l6 5-6 5M20 12H10a6 6 0 0 0-6 6") }

    val More: ImageVector by lazy { stroke("More", "M12 6h.01M12 12h.01M12 18h.01") }

    val Back: ImageVector by lazy { stroke("Back", "M15 5l-7 7 7 7") }

    val Filter: ImageVector by lazy { stroke("Filter", "M4 5h16l-6 8v6l-4-2v-4L4 5z") }

    val Tag: ImageVector by lazy { stroke("Tag", "M3 12V4h8l9 9-8 8-9-9zM7.5 7.5h.01") }

    val Close: ImageVector by lazy { stroke("Close", "M6 6l12 12M18 6L6 18") }

    val Clock: ImageVector by lazy { stroke("Clock", "M12 21a9 9 0 1 1 0-18 9 9 0 0 1 0 18zM12 7v5l3 2") }

    val Star: ImageVector by lazy { stroke("Star", "M12 4l2.4 5 5.6.6-4.2 3.8 1.2 5.6L12 16.2 7 19l1.2-5.6L4 9.6 9.6 9 12 4z") }

    val Seal: ImageVector by lazy {
        stroke(
            "Seal",
            "M12 3l2.2 2.1 3-.4.6 3 2.7 1.4-1.4 2.7 1.4 2.7-2.7 1.4-.6 3-3-.4L12 21" +
                "l-2.2-2.1-3 .4-.6-3-2.7-1.4L4.9 12 3.5 9.3l2.7-1.4.6-3 3 .4L12 3zM9 12l2 2 4-4",
        )
    }

    val Alert: ImageVector by lazy { stroke("Alert", "M12 4l9 16H3L12 4zM12 10v4M12 17h.01") }

    val Eye: ImageVector by lazy { stroke("Eye", "M2 12s4-7 10-7 10 7 10 7-4 7-10 7S2 12 2 12zM12 15a3 3 0 1 0 0-6 3 3 0 0 0 0 6z") }

    val Check: ImageVector by lazy { stroke("Check", "M5 12l5 5L20 7") }

    val EyeOff: ImageVector by lazy {
        stroke(
            "EyeOff",
            "M3 3l18 18M10.6 6.1A10 10 0 0 1 12 6c6 0 10 6 10 6a17 17 0 0 1-3 3.6" +
                "M6.6 6.6C3.8 8.4 2 12 2 12s4 7 10 7a9.6 9.6 0 0 0 4.4-1M9.9 9.9a3 3 0 0 0 4.2 4.2",
        )
    }

    val Arrow: ImageVector by lazy { stroke("Arrow", "M5 12h14M13 6l6 6-6 6") }

    val Mute: ImageVector by lazy { stroke("Mute", "M4 9v6h4l5 4V5L8 9H4zM17 9l4 6M21 9l-4 6") }

    val Shield: ImageVector by lazy { stroke("Shield", "M12 3l7 3v6c0 4.5-3 7.5-7 9-4-1.5-7-4.5-7-9V6l7-3zM9 12l2 2 4-4") }

    val Unsubscribe: ImageVector by lazy { stroke("Unsubscribe", "M4 6h16v12H4zM4 7l8 6 8-6M15 15l5 5M20 15l-5 5") }

    val Send: ImageVector by lazy { stroke("Send", "M4 12l16-8-6 16-2-6-8-2zM12 14l8-10") }

    val Draft: ImageVector by lazy { stroke("Draft", "M5 4h10l4 4v12H5zM9 12h6M9 16h4") }

    val Person: ImageVector by lazy { stroke("Person", "M12 12a4 4 0 1 0 0-8 4 4 0 0 0 0 8zM4 21a8 8 0 0 1 16 0") }

    val Box: ImageVector by lazy { stroke("Box", "M4 7l8-4 8 4v10l-8 4-8-4zM4 7l8 4 8-4M12 11v10") }
}

private fun stroke(
    name: String,
    pathData: String,
): ImageVector =
    ImageVector
        .Builder(name = name, defaultWidth = 24.dp, defaultHeight = 24.dp, viewportWidth = 24f, viewportHeight = 24f)
        .addPath(
            pathData = addPathNodes(pathData),
            stroke = SolidColor(Color.Black),
            strokeLineWidth = 1.8f,
            strokeLineCap = StrokeCap.Round,
            strokeLineJoin = StrokeJoin.Round,
        ).build()
