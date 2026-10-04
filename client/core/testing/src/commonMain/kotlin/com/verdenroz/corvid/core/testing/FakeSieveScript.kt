package com.verdenroz.corvid.core.testing

data class FakeSieveScript(
    val id: String,
    val name: String,
    var blobId: String,
    var isActive: Boolean = false,
)
