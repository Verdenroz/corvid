package com.verdenroz.corvid.core.keyring

/** A FileNode operation left the device key folder in a state [DeviceKeyRepository] refuses to guess through. */
sealed class DeviceKeyConflict(message: String) : Exception(message) {
    /** Two devices racing their first enrollment can each create a root `corvid-devices` folder. */
    class AmbiguousRootFolder(count: Int) :
        DeviceKeyConflict("$count corvid-devices root folders exist; refusing to guess which is current")

    class DestroyFailed(id: String, reason: String?) :
        DeviceKeyConflict("could not destroy FileNode $id: ${reason ?: "unknown reason"}")

    class CreateFailed(reason: String?) :
        DeviceKeyConflict("could not create FileNode: ${reason ?: "unknown reason"}")
}
