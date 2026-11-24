package com.palmace.jkapk.app.coroutines

import android.os.Handler
import android.os.Looper
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.InternalCoroutinesApi
import kotlinx.coroutines.MainCoroutineDispatcher
import kotlinx.coroutines.internal.MainDispatcherFactory

@OptIn(InternalCoroutinesApi::class)
class AndroidMainDispatcherFactory : MainDispatcherFactory {
    override val loadPriority: Int = Int.MAX_VALUE

    override fun createDispatcher(allFactories: List<MainDispatcherFactory>): MainCoroutineDispatcher {
        val mainLooper = Looper.getMainLooper()
        val handler = Handler(mainLooper)
        return HandlerMainDispatcher(handler, isImmediate = false)
    }
}

private class HandlerMainDispatcher(
    private val handler: Handler,
    private val isImmediate: Boolean
) : MainCoroutineDispatcher() {

    override val immediate: MainCoroutineDispatcher
        get() = if (isImmediate) this else HandlerMainDispatcher(handler, isImmediate = true)

    override fun isDispatchNeeded(context: kotlin.coroutines.CoroutineContext): Boolean {
        if (isImmediate) {
            return Looper.myLooper() != handler.looper
        }
        return true
    }

    override fun dispatch(context: kotlin.coroutines.CoroutineContext, block: Runnable) {
        handler.post(block)
    }

    override fun toString(): String =
        if (isImmediate) "HandlerMainDispatcher[immediate]" else "HandlerMainDispatcher"
}
