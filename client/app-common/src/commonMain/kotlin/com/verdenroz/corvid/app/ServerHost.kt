package com.verdenroz.corvid.app

/** The first label of the server's hostname, `n1` for `https://n1.example.test:4443`: the key pill's name for the machine holding the key. */
fun shortHost(baseUrl: String): String = baseUrl.substringAfter("://").substringBefore('/').substringBefore(':').substringBefore('.')
