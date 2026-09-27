package com.verdenroz.corvid.core.testing

data class FakeFileNode(
    val id: String,
    var parentId: String?,
    var name: String,
    var nodeType: String,
    var blobId: String? = null,
)
