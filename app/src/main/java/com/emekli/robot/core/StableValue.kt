package com.emekli.robot.core

/**
 * Debounce: bir değer ancak `windowMs` boyunca kesintisiz aynı kalırsa kabul edilir.
 * Gevşek şarj kablosu veya sıcaklık titremesi yüzünden kayıt yağmasını önler.
 */
class StableValue<T : Any>(initial: T, private val windowMs: Long) {
    var committed: T = initial
        private set
    private var pending: T? = null
    private var since = 0L

    val hasPending: Boolean get() = pending != null

    /** Kabul edilen değer değiştiyse true döner. */
    fun update(candidate: T, nowMs: Long): Boolean {
        if (candidate == committed) {
            pending = null
            return false
        }
        if (pending != candidate) {
            pending = candidate
            since = nowMs
            if (windowMs > 0) return false
        }
        if (nowMs - since >= windowMs) {
            committed = candidate
            pending = null
            return true
        }
        return false
    }
}
