package com.verdenroz.corvid.feature.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.verdenroz.corvid.core.data.stamp
import com.verdenroz.corvid.core.designsystem.CorvidTheme
import com.verdenroz.corvid.core.designsystem.component.PillButton
import com.verdenroz.corvid.core.designsystem.component.PrimaryButton
import com.verdenroz.corvid.core.model.UpdateReport
import com.verdenroz.corvid.core.model.UpdateResult
import com.verdenroz.corvid.core.text.asString
import com.verdenroz.corvid.feature.settings.resources.Res
import com.verdenroz.corvid.feature.settings.resources.settings_about
import com.verdenroz.corvid.feature.settings.resources.settings_about_app_version
import com.verdenroz.corvid.feature.settings.resources.settings_about_auto
import com.verdenroz.corvid.feature.settings.resources.settings_about_busy
import com.verdenroz.corvid.feature.settings.resources.settings_about_check
import com.verdenroz.corvid.feature.settings.resources.settings_about_checking
import com.verdenroz.corvid.feature.settings.resources.settings_about_last_checked
import com.verdenroz.corvid.feature.settings.resources.settings_about_latest
import com.verdenroz.corvid.feature.settings.resources.settings_about_off
import com.verdenroz.corvid.feature.settings.resources.settings_about_on
import com.verdenroz.corvid.feature.settings.resources.settings_about_result
import com.verdenroz.corvid.feature.settings.resources.settings_about_server_version
import com.verdenroz.corvid.feature.settings.resources.settings_about_unsupported
import com.verdenroz.corvid.feature.settings.resources.settings_about_update_now
import com.verdenroz.corvid.feature.settings.resources.settings_about_updating
import com.verdenroz.corvid.feature.settings.resources.settings_result_current
import com.verdenroz.corvid.feature.settings.resources.settings_result_detail
import com.verdenroz.corvid.feature.settings.resources.settings_result_failed
import com.verdenroz.corvid.feature.settings.resources.settings_result_rolled_back
import com.verdenroz.corvid.feature.settings.resources.settings_result_skipped
import com.verdenroz.corvid.feature.settings.resources.settings_result_updated
import kotlinx.datetime.TimeZone
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.stringResource
import kotlin.time.Clock

@Composable
internal fun AboutSection(
    appVersion: String,
    state: AboutState,
    viewModel: AboutViewModel,
) {
    SettingsSection(stringResource(Res.string.settings_about)) {
        FactRow(stringResource(Res.string.settings_about_app_version), appVersion)
        when (state) {
            is AboutState.Loading -> Unit
            is AboutState.Unsupported -> Note(stringResource(Res.string.settings_about_unsupported))
            is AboutState.Failed -> Note(state.message.asString(), CorvidTheme.colors.danger)
            is AboutState.Ready -> ServerRows(state, viewModel)
        }
    }
}

@Composable
private fun ServerRows(
    state: AboutState.Ready,
    viewModel: AboutViewModel,
) {
    val report = state.report
    FactRow(stringResource(Res.string.settings_about_server_version), report.running)
    report.latest?.let { FactRow(stringResource(Res.string.settings_about_latest), it) }
    report.checkedAt?.let { checkedAt ->
        FactRow(
            stringResource(Res.string.settings_about_last_checked),
            stamp(checkedAt, TimeZone.currentSystemDefault(), Clock.System.now()).asString(),
        )
    }
    FactRow(stringResource(Res.string.settings_about_result), report.resultLine())
    FactRow(
        stringResource(Res.string.settings_about_auto),
        stringResource(if (report.auto) Res.string.settings_about_on else Res.string.settings_about_off),
    )
    state.failure?.let { Note(it.asString(), CorvidTheme.colors.danger) }
    when {
        state.activity == UpdateActivity.Checking -> Note(stringResource(Res.string.settings_about_checking))
        state.activity == UpdateActivity.Updating -> Note(stringResource(Res.string.settings_about_updating))
        report.busy -> Note(stringResource(Res.string.settings_about_busy))
        else ->
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.padding(vertical = 14.dp),
            ) {
                PillButton(stringResource(Res.string.settings_about_check), null, viewModel::check)
                if (report.updateAvailable) PrimaryButton(stringResource(Res.string.settings_about_update_now), viewModel::apply)
            }
    }
}

@Composable
private fun Note(
    text: String,
    color: Color = CorvidTheme.colors.inkMuted,
) {
    Text(text, style = CorvidTheme.type.meta, color = color, modifier = Modifier.padding(vertical = 14.dp))
}

@Composable
private fun UpdateReport.resultLine(): String {
    val result = stringResource(result.label())
    return detail?.let { stringResource(Res.string.settings_result_detail, result, it) } ?: result
}

private fun UpdateResult.label(): StringResource =
    when (this) {
        UpdateResult.Current -> Res.string.settings_result_current
        UpdateResult.Updated -> Res.string.settings_result_updated
        UpdateResult.RolledBack -> Res.string.settings_result_rolled_back
        UpdateResult.Failed -> Res.string.settings_result_failed
        UpdateResult.Skipped -> Res.string.settings_result_skipped
    }
