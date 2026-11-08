package com.noircipher.shade

import java.util.concurrent.ConcurrentHashMap

/**
 * One shared instance per learning/stat store for the whole process. Before this, the keyboard
 * service, the stats screen and the "reset learned data" action each built their own copy, so a
 * reset cleared a copy while the service's live copy kept its old data and wrote it back.
 */
object LiveStores {
    @PublishedApi
    internal val instances = ConcurrentHashMap<Class<*>, Any>()

    @Suppress("UNCHECKED_CAST")
    inline fun <reified T : Any> get(noinline create: () -> T): T =
        instances.getOrPut(T::class.java, create) as T
}
