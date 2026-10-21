package top.wkbin.taixu.runtime.remote

import java.security.MessageDigest
import top.wkbin.taixu.core.model.RemoteServer

/** Opaque identity for standalone protocol owners; contains no authentication material. */
fun remoteConnectionScope(server: RemoteServer): String = MessageDigest.getInstance("SHA-256")
    .digest(server.toString().toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
