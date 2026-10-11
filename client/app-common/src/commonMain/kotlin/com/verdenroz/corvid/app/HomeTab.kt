package com.verdenroz.corvid.app

import com.verdenroz.corvid.feature.mailbox.LedgerTab

/** The home tabs in display order, mail folders before the views of who wrote it; [ledger] is null for a view that is not built yet. */
enum class HomeTab(val ledger: LedgerTab?) {
    Inbox(LedgerTab.Inbox),
    Starred(LedgerTab.Starred),
    Sent(LedgerTab.Sent),
    Drafts(LedgerTab.Drafts),
    Archive(LedgerTab.Archive),
    Junk(LedgerTab.Junk),
    Trash(LedgerTab.Trash),
    People(null),
    Sources(null),
    Labels(null),
}
