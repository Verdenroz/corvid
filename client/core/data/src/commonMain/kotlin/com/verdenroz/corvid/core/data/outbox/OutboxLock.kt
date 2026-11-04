package com.verdenroz.corvid.core.data.outbox

import java.io.File
import java.io.RandomAccessFile
import java.nio.channels.FileLock
import java.nio.channels.OverlappingFileLockException

/** Held by the one process draining a directory; released on [close] or when the process ends. */
internal class OutboxLock private constructor(private val file: RandomAccessFile, private val lock: FileLock) : AutoCloseable {
    override fun close() {
        lock.release()
        file.close()
    }

    companion object {
        /** Null while another holder, in this process or any other, has the directory. */
        fun acquire(directory: File): OutboxLock? {
            val file = RandomAccessFile(File(directory, ".lock"), "rw")
            val lock =
                try {
                    file.channel.tryLock()
                } catch (_: OverlappingFileLockException) {
                    null
                }
            if (lock == null) file.close()
            return lock?.let { OutboxLock(file, it) }
        }
    }
}
