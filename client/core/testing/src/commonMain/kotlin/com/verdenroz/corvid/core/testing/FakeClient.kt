package com.verdenroz.corvid.core.testing

import com.verdenroz.corvid.core.jmap.AuthLatch
import com.verdenroz.corvid.core.jmap.JmapClient
import com.verdenroz.corvid.core.jmap.createJmapHttpClient

/** A client already connected to this fake, as a screen's repositories would hold one. */
suspend fun FakeJmapServer.client(): JmapClient = JmapClient.connect(createJmapHttpClient(engine), AuthLatch(), "http://127.0.0.1:9999")
