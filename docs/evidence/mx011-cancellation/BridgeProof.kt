import kotlinx.coroutines.*
import java.lang.reflect.InvocationTargetException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.coroutines.intrinsics.suspendCoroutineUninterceptedOrReturn

// Evidence fixture only: mirrors the SDK's public suspend JVM signature.
class Endpoint(private val body: suspend () -> String) {
    suspend fun sync(): String = body()
}
private suspend fun invoke(endpoint: Endpoint): String =
    suspendCoroutineUninterceptedOrReturn { continuation ->
        try {
            Endpoint::class.java.getMethod("sync", kotlin.coroutines.Continuation::class.java)
                .invoke(endpoint, continuation)
        } catch (e: InvocationTargetException) {
            throw e.targetException
        }
    }

// Single-threaded ownership model; production must serialize these transitions.
private class Owner(private val scope: CoroutineScope, private val now: () -> Long) {
    var generation = 0
    var slot: Job? = null
    var published = 0
    var retired = 0
    var releases = 0
    private var valid = true
    fun start(endpoint: Endpoint, deadline: Long): Job {
        check(slot == null)
        val captured = generation
        val job = scope.launch(start = CoroutineStart.LAZY) {
            val value = invoke(endpoint)
            ensureActive()
            if (valid && generation == captured && now() < deadline && value == "ok") published++
        }
        slot = job
        job.invokeOnCompletion { check(slot === job); slot = null; releases++ }
        return job
    }
    fun invalidate() { valid = false; generation++; slot?.cancel() }
    suspend fun retire() {
        invalidate()
        slot?.join() // Actual invocation job, not the foreground waiter.
        if (retired == 0) retired++
    }
}

fun main() = runBlocking {
    val errors = mutableListOf<Throwable>()
    val supervisor = SupervisorJob()
    val scope = CoroutineScope(coroutineContext + supervisor + CoroutineExceptionHandler { _, e -> errors += e })
    var ticks = 0L
    fun owner() = Owner(scope) { ticks }
    suspend fun case(name: String, body: suspend () -> Unit) { body(); println("PASS $name") }
    case("immediate completion") {
        val o = owner(); o.start(Endpoint { "ok" }, 100).also { it.start(); it.join() }
        check(o.published == 1 && o.releases == 1)
    }
    case("suspended completion and overlap rejection") {
        val gate = CompletableDeferred<String>(); val entered = CompletableDeferred<Unit>()
        val o = owner(); val j = o.start(Endpoint { entered.complete(Unit); gate.await() }, 100)
        j.start(); entered.await()
        check(runCatching { o.start(Endpoint { "ok" }, 100) }.isFailure)
        gate.complete("ok"); j.join(); check(o.published == 1 && o.releases == 1)
    }
    for (suspended in listOf(false, true)) case("failure suspended=$suspended") {
        val expected = IllegalStateException("fixture"); val o = owner()
        o.start(Endpoint { if (suspended) yield(); throw expected }, 100).also { it.start(); it.join() }
        check(errors.remove(expected) && o.published == 0 && o.releases == 1)
    }
    case("cancellation before start") {
        var entered = false; val o = owner()
        val j = o.start(Endpoint { entered = true; "ok" }, 100)
        o.invalidate(); j.start(); j.join()
        check(!entered && o.releases == 1 && o.published == 0)
    }
    for (successFirst in listOf(false, true)) case("deadline race successFirst=$successFirst") {
        ticks = 0; val o = owner(); val gate = CompletableDeferred<String>()
        val j = o.start(Endpoint { gate.await() }, 10); j.start(); yield()
        if (successFirst) { gate.complete("ok"); j.join(); ticks = 10; o.invalidate() }
        else { ticks = 10; gate.complete("ok"); j.join(); o.invalidate() }
        check(o.published == if (successFirst) 1 else 0)
        check(o.releases == 1)
    }
    ticks = 0
    for (reason in listOf("logout", "close", "login replacement", "restore replacement", "timeout")) {
        case("$reason drains terminal unwind") {
            val entered = CompletableDeferred<Unit>(); val cleaning = CompletableDeferred<Unit>()
            val drain = CompletableDeferred<Unit>(); val o = owner()
            val j = o.start(Endpoint {
                try { entered.complete(Unit); awaitCancellation() }
                finally { withContext(NonCancellable) { cleaning.complete(Unit); drain.await() } }
            }, 100)
            j.start(); entered.await(); o.invalidate(); cleaning.await()
            val retirement = launch { o.retire() }; yield()
            check(o.slot === j && o.retired == 0 && o.releases == 0 && !retirement.isCompleted)
            drain.complete(Unit); retirement.join(); o.retire()
            check(o.retired == 1 && o.releases == 1 && o.published == 0)
        }
    }
    case("foreground timeout returns before cleanup; join still drains") {
        val entered = CompletableDeferred<Unit>(); val drain = CompletableDeferred<Unit>()
        val o = owner(); val j = o.start(Endpoint {
            try { entered.complete(Unit); awaitCancellation() }
            finally { withContext(NonCancellable) { drain.await() } }
        }, 100)
        j.start(); entered.await()
        check(withTimeoutOrNull(20) { j.join(); true } == null)
        o.invalidate(); yield(); check(o.slot === j && o.releases == 0)
        drain.complete(Unit); j.join(); check(o.releases == 1)
    }
    case("thread interruption requests cancellation and preserves interrupt") {
        val entered = CountDownLatch(1); val cleaned = CountDownLatch(1)
        var preserved = false
        val thread = Thread {
            try {
                runBlocking {
                    invoke(Endpoint {
                        try { entered.countDown(); awaitCancellation() }
                        finally { cleaned.countDown() }
                    })
                }
            } catch (_: InterruptedException) { Thread.currentThread().interrupt(); preserved = Thread.currentThread().isInterrupted }
        }
        thread.start(); check(entered.await(5, TimeUnit.SECONDS)); thread.interrupt(); thread.join(5000)
        check(!thread.isAlive && preserved && cleaned.count == 0L)
    }
    check(errors.isEmpty()); supervisor.cancelAndJoin()
    println("Host ownership proof only; no native library loaded.")
}
