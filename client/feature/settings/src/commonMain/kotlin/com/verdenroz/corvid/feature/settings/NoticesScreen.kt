package com.verdenroz.corvid.feature.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.verdenroz.corvid.core.data.stamp
import com.verdenroz.corvid.core.designsystem.CorvidSpacing
import com.verdenroz.corvid.core.designsystem.CorvidTheme
import com.verdenroz.corvid.core.designsystem.LocalWideLayout
import com.verdenroz.corvid.core.model.Notice
import com.verdenroz.corvid.core.text.asString
import com.verdenroz.corvid.core.ui.PageHeader
import com.verdenroz.corvid.core.ui.QuietPane
import com.verdenroz.corvid.feature.settings.resources.Res
import com.verdenroz.corvid.feature.settings.resources.notices_empty
import com.verdenroz.corvid.feature.settings.resources.notices_title
import com.verdenroz.corvid.feature.settings.resources.notices_unsupported
import com.verdenroz.corvid.feature.settings.resources.settings_back
import kotlinx.datetime.TimeZone
import org.jetbrains.compose.resources.stringResource
import kotlin.time.Clock

/** What the server has said about itself, newest first. */
@Composable
fun NoticesScreen(
    viewModel: NoticesViewModel,
    onBack: () -> Unit,
) {
    val state by viewModel.state.collectAsState()
    Column(modifier = Modifier.fillMaxSize().background(CorvidTheme.colors.surface)) {
        PageHeader(stringResource(Res.string.notices_title), stringResource(Res.string.settings_back), onBack)
        when (val current = state) {
            is NoticesState.Loading -> Unit
            is NoticesState.Unsupported -> QuietPane(stringResource(Res.string.notices_unsupported))
            is NoticesState.Failed -> QuietPane(current.message.asString())
            is NoticesState.Ready ->
                if (current.notices.isEmpty()) QuietPane(stringResource(Res.string.notices_empty)) else NoticeList(current.notices)
        }
    }
}

@Composable
private fun NoticeList(notices: List<Notice>) {
    val gutter = CorvidSpacing.gutter(LocalWideLayout.current)
    val now = Clock.System.now()
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(horizontal = gutter, vertical = 8.dp)) {
        items(notices, key = { it.id }) { notice ->
            Column(Modifier.widthIn(max = 640.dp)) {
                Column(verticalArrangement = Arrangement.spacedBy(2.dp), modifier = Modifier.fillMaxWidth().padding(vertical = 14.dp)) {
                    Text(notice.title, style = CorvidTheme.type.name, fontWeight = FontWeight.SemiBold)
                    Text(notice.detail, style = CorvidTheme.type.meta, color = CorvidTheme.colors.inkMuted)
                    Text(
                        stamp(notice.at, TimeZone.currentSystemDefault(), now).asString(),
                        style = CorvidTheme.type.meta,
                        color = CorvidTheme.colors.inkFaint,
                    )
                }
                HorizontalDivider(color = CorvidTheme.colors.line)
            }
        }
    }
}
