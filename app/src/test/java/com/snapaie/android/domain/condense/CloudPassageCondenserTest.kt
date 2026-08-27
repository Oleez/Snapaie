package com.snapaie.android.domain.condense

import com.snapaie.android.data.cloud.CloudCondensed
import com.snapaie.android.data.cloud.CloudResult
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Condensing a book in the cloud, and what happens when that stops working.
 *
 * The properties worth pinning are all about failure, because the cloud is the part that
 * can be taken away mid-book: credit runs out, signal drops, the backend restarts. None of
 * those may cost the reader the book — the phone finishes it, slower and rougher.
 */
class CloudPassageCondenserTest {

    /** A stand-in for the phone: always available, always answers, never the cloud. */
    private class LocalStub(var calls: Int = 0) : PassageCondenser {
        override fun isReady() = true
        override suspend fun condense(
            sourceText: String,
            ledger: StoryLedger,
            previousTail: String,
            budgetWords: Int,
            onToken: (String) -> Unit,
        ): CondensedBeat {
            calls++
            return CondensedBeat(
                prose = "LOCAL: ${sourceText.take(20)}",
                ledger = ledger,
                words = 3,
                attempts = 1,
                usedFallback = true,
            )
        }
    }

    private fun passage(n: Int) = "Passage number $n runs on for a while about something. " +
        "It carries an event and a name worth keeping in the shorter version."

    private suspend fun runBook(
        condenser: PassageCondenser,
        count: Int,
    ): List<String> = (0 until count).map {
        condenser.condense(passage(it), StoryLedger(), "", 40) {}.prose
    }

    @Test
    fun `running out of credit finishes the book on the phone`() = runTest {
        // The failure a free user will actually hit, halfway through a long book.
        val local = LocalStub()
        var told = false
        val condenser = CloudPassageCondenser(
            cloud = FakeCloud(CloudResult.OutOfCredit),
            local = local,
            onCreditExhausted = { told = true },
            batchSize = 2,
        )

        val out = runBook(condenser, 6)

        assertEquals("every passage should have been condensed", 6, out.size)
        assertTrue("the book did not finish", out.all { it.isNotBlank() })
        assertTrue("the reader was never told credit ran out", told)
    }

    @Test
    fun `once credit is gone it stops asking`() = runTest {
        // Asking again for every remaining passage would be hundreds of round trips, each
        // one slower than the local path it is delaying.
        val cloud = FakeCloud(CloudResult.OutOfCredit)
        val condenser = CloudPassageCondenser(cloud, LocalStub(), onCreditExhausted = {}, batchSize = 2)

        runBook(condenser, 20)

        assertEquals("kept asking after being told no", 1, cloud.calls)
    }

    @Test
    fun `a failed request costs that batch and not the book`() = runTest {
        val local = LocalStub()
        val condenser = CloudPassageCondenser(
            cloud = FakeCloud(CloudResult.Failed("no signal")),
            local = local,
            batchSize = 2,
        )

        val out = runBook(condenser, 4)

        assertEquals(4, out.size)
        assertTrue("nothing was produced", out.all { it.startsWith("LOCAL:") })
    }

    @Test
    fun `a passage the cloud skipped is condensed on the phone`() = runTest {
        // A model asked for ten blocks sometimes returns nine. The missing one must cost
        // itself and nothing after it — matching answers by position rather than by the
        // passage they belong to is how chapter four ends up under chapter three.
        val local = LocalStub()
        // Both passages go in one request; only the first comes back.
        val cloud = FakeCloud(
            answer = CloudResult.Ok(listOf(CloudCondensed(0, "CLOUD: first")), pagesLeft = 100),
            subsequent = CloudResult.Ok(emptyList(), pagesLeft = 100),
        )
        val condenser = CloudPassageCondenser(
            cloud = cloud,
            local = local,
            lookahead = { listOf(passage(1)) },
            batchSize = 2,
        )

        val out = runBook(condenser, 2)

        assertTrue("the cloud's answer was discarded", out[0].startsWith("CLOUD:"))
        assertTrue("the skipped passage was lost", out[1].startsWith("LOCAL:"))
    }

    @Test
    fun `looking ahead means one request covers several passages`() = runTest {
        // The whole point of the cloud path. One request per passage would spend the
        // saving on round trips: a long book is 150 passages, and 150 calls is minutes
        // of latency before any work happens.
        val local = LocalStub()
        val upcoming = (1 until 5).map { passage(it) }
        val cloud = FakeCloud(
            CloudResult.Ok((0 until 5).map { CloudCondensed(it, "CLOUD: $it") }, pagesLeft = 100),
        )
        val condenser = CloudPassageCondenser(
            cloud = cloud,
            local = local,
            lookahead = { upcoming },
            batchSize = 5,
        )

        val out = runBook(condenser, 5)

        assertEquals("five passages should have cost one request", 1, cloud.calls)
        assertEquals("the phone should not have been needed", 0, local.calls)
        assertTrue("some passage did not come from the cloud", out.all { it.startsWith("CLOUD:") })
    }

    @Test
    fun `with no backend configured everything runs on the phone`() = runTest {
        val local = LocalStub()
        val condenser = CloudPassageCondenser(
            cloud = FakeCloud(CloudResult.Failed("unused"), isConfigured = false),
            local = local,
            batchSize = 2,
        )

        runBook(condenser, 5)

        assertEquals("the phone should have done all of it", 5, local.calls)
    }
}

/** A backend that always answers the same way, so failure modes are reachable in a test. */
private class FakeCloud(
    private val answer: CloudResult<List<CloudCondensed>>,
    override val isConfigured: Boolean = true,
    /**
     * What later calls return. Defaults to repeating [answer]; a test modelling a passage
     * the model skipped sets this to an empty success, so asking again does not rescue it.
     */
    private val subsequent: CloudResult<List<CloudCondensed>>? = null,
) : com.snapaie.android.data.cloud.CloudCondenseApi {
    var calls = 0
        private set

    override suspend fun condenseBatch(
        passages: List<com.snapaie.android.data.cloud.CloudPassage>,
        ledger: String,
        style: String,
        pages: Int,
    ): CloudResult<List<CloudCondensed>> {
        calls++
        return if (calls == 1 || subsequent == null) answer else subsequent
    }
}
