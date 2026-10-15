package com.verdenroz.corvid.app

/** Takes each open request as it becomes pending, for as long as the caller's scope lives. */
internal suspend fun AppPresence.deliverTo(navigator: Navigator) {
    pending.collect { if (it != null) take()?.let(navigator::show) }
}
