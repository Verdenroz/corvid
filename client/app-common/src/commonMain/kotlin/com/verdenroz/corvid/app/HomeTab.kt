package com.verdenroz.corvid.app

import com.verdenroz.corvid.feature.mailbox.LedgerTab

/** The home tabs in display order; [ledger] is null for a view that is not built yet. */
enum class HomeTab(val ledger: LedgerTab?) {
    Inbox(LedgerTab.Inbox),
    People(null),
    Sources(null),
    Starred(LedgerTab.Starred),
    Sent(LedgerTab.Sent),
    Drafts(LedgerTab.Drafts),
    Trash(LedgerTab.Trash),
    Labels(null),
}
