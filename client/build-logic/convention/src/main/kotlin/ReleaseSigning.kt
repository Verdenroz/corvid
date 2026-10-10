import java.io.File

class ReleaseSigning(val storeFile: File, val storePassword: String, val keyAlias: String, val keyPassword: String)

/** Null unless all four CORVID_KEY* variables are set, so unsigned builds keep working without secrets. */
fun releaseSigning(): ReleaseSigning? {
    val file = System.getenv("CORVID_KEYSTORE_FILE") ?: return null
    return ReleaseSigning(
        storeFile = File(file),
        storePassword = System.getenv("CORVID_KEYSTORE_PASSWORD") ?: return null,
        keyAlias = System.getenv("CORVID_KEY_ALIAS") ?: return null,
        keyPassword = System.getenv("CORVID_KEY_PASSWORD") ?: return null,
    )
}
