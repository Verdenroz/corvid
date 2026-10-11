package com.verdenroz.corvid.feature.mailbox

import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.dp
import com.verdenroz.corvid.core.designsystem.CorvidTheme
import com.verdenroz.corvid.core.model.Seal
import com.verdenroz.corvid.core.model.SenderKind
import com.verdenroz.corvid.core.text.asString
import com.verdenroz.corvid.core.ui.provenance.SenderAvatar
import com.verdenroz.corvid.feature.mailbox.resources.Res
import com.verdenroz.corvid.feature.mailbox.resources.card_between
import com.verdenroz.corvid.feature.mailbox.resources.people_empty
import org.jetbrains.compose.resources.stringResource

/** Everyone who wrote or was written to in the window, most recent first; a row opens that person's card. */
@Composable
fun PeopleScreen(
    viewModel: DirectoryViewModel,
    onOpenSender: (String) -> Unit,
) {
    DirectoryList(viewModel, stringResource(Res.string.people_empty), isEmpty = { it.people.isEmpty() }) { directory ->
        items(directory.people, key = { it.address.email.lowercase() }) { person ->
            val name = person.address.displayName()
            val suspect = person.seal is Seal.Failed
            DirectoryRow(
                name = name,
                detail = if (suspect) senderAddress(person.address.email, true).asString() else person.address.email,
                detailColor = if (suspect) CorvidTheme.colors.danger else CorvidTheme.colors.inkMuted,
                last = person.last,
                onClick = { onOpenSender(person.address.email) },
                leading = { SenderAvatar(name, SenderKind.Person, person.seal, size = 40.dp) },
            ) {
                Text(
                    stringResource(Res.string.card_between, person.received, person.sent),
                    style = CorvidTheme.type.metaNumeric,
                    color = CorvidTheme.colors.inkFaint,
                )
            }
        }
    }
}
