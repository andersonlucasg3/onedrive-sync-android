package com.anderson.onedrivesync.ui.vm

import com.anderson.onedrivesync.sync.CycleResult
import com.anderson.onedrivesync.sync.SyncState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
class PairProgressTest {

    @Test
    fun `never when there is no result and no previous sync`() {
        assertEquals(PairProgress.Never, pairProgress(lastSyncAt = null, result = null, running = null))
    }

    @Test
    fun `running wins over a stored result`() {
        val running = SyncState.Running("Teste", "calculando hashes", done = 3, total = 10, phase = 3, phases = 5)

        val progress = pairProgress(LAST, ok(downloaded = 2), running)

        assertEquals(PairProgress.Running("calculando hashes", 3, 10, 3, 5), progress)
    }

    @Test
    fun `running carries the phase numbers and keeps done over total`() {
        val running = SyncState.Running("Teste", "lendo OneDrive · página 42 · 8.400 itens", 42, 0, 1, 5)

        val progress = pairProgress(null, null, running) as PairProgress.Running

        assertEquals(1, progress.phase)
        assertEquals(5, progress.phases)
        assertEquals("lendo OneDrive · página 42 · 8.400 itens", progress.step)
    }

    @Test
    fun `elapsed text is human readable from seconds to hours`() {
        assertEquals("há 0s", elapsedText(0))
        assertEquals("há 45s", elapsedText(45_000))
        assertEquals("há 1m20s", elapsedText(80_000))
        assertEquals("há 2m", elapsedText(120_000))
        assertEquals("há 1h05m", elapsedText(3_900_000))
        assertEquals("há 0s", elapsedText(-1_000))
    }

    @Test
    fun `ok when the last result has no failure`() {
        val result = ok(downloaded = 1, uploaded = 2)

        assertEquals(PairProgress.Ok(result), pairProgress(LAST, result, running = null))
    }

    @Test
    fun `failed when actions failed even with ok from a corrupted row`() {
        val result = result(ok = true, failed = 1)

        assertEquals(PairProgress.Failed(result), pairProgress(LAST, result, running = null))
    }

    @Test
    fun `failed when the cycle carries an error`() {
        val result = result(ok = false, failed = 0, error = "HTTP 500")

        assertEquals(PairProgress.Failed(result), pairProgress(null, result, running = null))
    }

    @Test
    fun `a legacy pair with only lastSyncAt reports ok with no transfers`() {
        val progress = pairProgress(LAST, result = null, running = null)

        assertTrue(progress is PairProgress.Ok)
        val ok = progress as PairProgress.Ok
        assertTrue(ok.result.ok)
        assertEquals(LAST, ok.result.finishedAt)
        assertEquals("Nenhuma alteração", cycleSummaryText(ok.result))
    }

    @Test
    fun `summary text uses singular and plural`() {
        assertEquals("1 baixado", cycleSummaryText(ok(downloaded = 1)))
        assertEquals("2 baixados", cycleSummaryText(ok(downloaded = 2)))
        assertEquals("1 enviado", cycleSummaryText(ok(uploaded = 1)))
        assertEquals("2 enviados", cycleSummaryText(ok(uploaded = 2)))
        assertEquals("1 baixado, 2 enviados", cycleSummaryText(ok(downloaded = 1, uploaded = 2)))
        assertEquals("2 baixados, 1 enviado", cycleSummaryText(ok(downloaded = 2, uploaded = 1)))
    }

    @Test
    fun `summary text says no change when both counts are zero`() {
        assertEquals("Nenhuma alteração", cycleSummaryText(ok()))
    }

    @Test
    fun `first error line is extracted and trimmed`() {
        assertEquals(
            "primeira linha",
            firstErrorLine(result(ok = false, failed = 1, error = "  primeira linha \nsegunda")),
        )
        assertNull(firstErrorLine(ok()))
        assertNull(firstErrorLine(null))
    }

    private fun result(
        ok: Boolean = true,
        downloaded: Int = 0,
        uploaded: Int = 0,
        failed: Int = 0,
        error: String? = null,
    ): CycleResult = CycleResult(
        finishedAt = NOW,
        ok = ok,
        downloaded = downloaded,
        uploaded = uploaded,
        failed = failed,
        pending = 0,
        conflicts = 0,
        error = error,
    )

    private fun ok(downloaded: Int = 0, uploaded: Int = 0): CycleResult =
        result(ok = true, downloaded = downloaded, uploaded = uploaded)

    private companion object {
        const val NOW = 1_700_000_000_000L
        const val LAST = 1_699_999_000_000L
    }
}
