package com.anderson.onedrivesync.ui.vm

import com.anderson.onedrivesync.sync.CycleResult
import com.anderson.onedrivesync.sync.SyncState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SyncStatusTest {

    @Test
    fun `never when there is no result and no previous sync`() {
        assertEquals(
            SyncStatus.Never(null),
            syncStatus(running = null, global = null, lastSyncAt = null, now = NOW),
        )
    }

    @Test
    fun `never keeps the timestamp of a pair without a stored result`() {
        assertEquals(SyncStatus.Never(LAST), syncStatus(running = null, global = null, lastSyncAt = LAST, now = NOW))
    }

    @Test
    fun `running wins over a stored result`() {
        val running = SyncState.Running("Teste", "calculando hashes", done = 3, total = 10, phase = 3, phases = 5)

        val status = syncStatus(running, ok(downloaded = 2), LAST, NOW)

        assertEquals(SyncStatus.Running("Teste", "calculando hashes", 3, 10, 3, 5, NOW), status)
    }

    @Test
    fun `running wins over a failed cycle`() {
        val running = SyncState.Running("Teste", "baixar foto.jpg", done = 1, total = 2, phase = 5, phases = 5)

        val status = syncStatus(running, result(ok = false, failed = 2), LAST, NOW)

        assertTrue(status is SyncStatus.Running)
    }

    @Test
    fun `running without a pair name reports null`() {
        val running = SyncState.Running("", "lendo OneDrive", done = 0, total = 0)

        assertEquals(SyncStatus.Running(null, "lendo OneDrive", 0, 0, 0, 0, NOW), syncStatus(running, null, null, NOW))
    }

    @Test
    fun `failed when actions failed even with ok from a corrupted row`() {
        val result = result(ok = true, failed = 1)

        assertEquals(SyncStatus.Failed(result), syncStatus(null, result, LAST, NOW))
    }

    @Test
    fun `failed wins over done when the cycle carries an error`() {
        val result = result(ok = false, failed = 0, error = "HTTP 500")

        assertEquals(SyncStatus.Failed(result), syncStatus(null, result, LAST, NOW))
    }

    @Test
    fun `done when the last result has no failure`() {
        val result = ok(downloaded = 1, uploaded = 2)

        assertEquals(SyncStatus.Done(result), syncStatus(null, result, LAST, NOW))
    }

    @Test
    fun `headline shows the phase and the step while running`() {
        assertEquals("Etapa 2 de 5 · lendo OneDrive", statusHeadline(running(step = "lendo OneDrive", phase = 2), NOW))
    }

    @Test
    fun `headline omits the phase when the caller numbers none`() {
        assertEquals("lendo OneDrive", statusHeadline(running(step = "lendo OneDrive", phase = 0), NOW))
    }

    @Test
    fun `headline keeps only the phase name when the step carries counters`() {
        val status = running(step = "lendo OneDrive · página 3 · 600 itens", phase = 1)

        assertEquals("Etapa 1 de 5 · lendo OneDrive", statusHeadline(status, NOW))
    }

    @Test
    fun `headline for the finished states`() {
        assertEquals("Tudo sincronizado", statusHeadline(SyncStatus.Done(ok()), NOW))
        assertEquals("Último ciclo falhou", statusHeadline(SyncStatus.Failed(result(ok = false, failed = 1)), NOW))
        assertEquals("Nunca sincronizado", statusHeadline(SyncStatus.Never(null), NOW))
    }

    @Test
    fun `detail reuses the counters the delta step carries`() {
        val status = running(step = "lendo OneDrive · página 3 · 600 itens", done = 3, total = 0, phase = 1)

        assertEquals("página 3 · 600 itens", statusDetail(status, NOW))
    }

    @Test
    fun `detail falls back to done over total`() {
        assertEquals("3/10", statusDetail(running(step = "calculando hashes", done = 3, total = 10), NOW))
    }

    @Test
    fun `detail is empty when a running step has no counters`() {
        assertEquals("", statusDetail(running(step = "planejando", done = 0, total = 0), NOW))
    }

    @Test
    fun `done detail shows the relative time and the transfers`() {
        val status = SyncStatus.Done(ok(downloaded = 12, uploaded = 1))

        assertEquals("há 5 min · 12 baixados, 1 enviado", statusDetail(status, NOW))
    }

    @Test
    fun `done detail omits zero transfers and says no change when there was none`() {
        assertEquals("há 5 min · 1 baixado", statusDetail(SyncStatus.Done(ok(downloaded = 1)), NOW))
        assertEquals("há 5 min · Nenhuma alteração", statusDetail(SyncStatus.Done(ok()), NOW))
    }

    @Test
    fun `failed detail shows the first error line`() {
        val status = SyncStatus.Failed(result(ok = false, failed = 1, error = "  primeira linha \nsegunda"))

        assertEquals("primeira linha", statusDetail(status, NOW))
    }

    @Test
    fun `failed detail falls back to the failure count with correct plural`() {
        assertEquals("1 ação falhou", statusDetail(SyncStatus.Failed(result(ok = false, failed = 1)), NOW))
        assertEquals("3 ações falharam", statusDetail(SyncStatus.Failed(result(ok = false, failed = 3)), NOW))
    }

    @Test
    fun `failed detail is empty when there is nothing to report`() {
        assertEquals("", statusDetail(SyncStatus.Failed(null), NOW))
    }

    @Test
    fun `never detail invites the user to sync`() {
        assertEquals("Toque em Sincronizar para começar", statusDetail(SyncStatus.Never(null), NOW))
    }

    @Test
    fun `card line counts the files of that pair last cycle`() {
        assertEquals("há 5 min · 12 arquivos", pairLastSyncText(ok(downloaded = 12), LAST, NOW))
        assertEquals("há 5 min · 1 arquivo", pairLastSyncText(ok(uploaded = 1), LAST, NOW))
        assertEquals("há 5 min · Nenhuma alteração", pairLastSyncText(ok(), LAST, NOW))
    }

    @Test
    fun `card line falls back to the stored timestamp and to null`() {
        assertEquals("há 5 min", pairLastSyncText(null, LAST, NOW))
        assertNull(pairLastSyncText(null, null, NOW))
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
    fun `active operation names the pair, the normalized verb and the counter`() {
        val status = running(
            step = "enviar WhatsApp/x.jpg",
            done = 161,
            total = 1_962,
            phase = 5,
            pairName = "WhatsApp Images",
        )

        assertEquals("WhatsApp Images · enviando WhatsApp/x.jpg · 161/1.962", activeOperationText(status))
    }

    @Test
    fun `active operation normalizes the download and move verbs`() {
        assertEquals("Teste · baixando Docs/a.txt", activeOperationText(running("baixar Docs/a.txt")))
        assertEquals("Teste · movendo local a.txt → b.txt", activeOperationText(running("mover local a.txt → b.txt")))
        assertEquals("Teste · movendo remoto a.txt → b.txt", activeOperationText(running("mover remoto a.txt → b.txt")))
    }

    @Test
    fun `active operation is absent outside a file operation`() {
        assertNull(activeOperationText(running("lendo OneDrive · página 3 · 600 itens", phase = 1)))
        assertNull(activeOperationText(running("calculando hashes", done = 3, total = 10, phase = 3)))
        assertNull(activeOperationText(running("planejando", phase = 4)))
        assertNull(activeOperationText(SyncStatus.Done(ok())))
        assertNull(activeOperationText(SyncStatus.Failed(null)))
        assertNull(activeOperationText(SyncStatus.Never(null)))
    }

    @Test
    fun `active operation omits the pair and the counter when there is neither`() {
        assertEquals("enviando x.jpg", activeOperationText(running("enviar x.jpg", pairName = null)))
    }

    private fun running(
        step: String,
        done: Int = 0,
        total: Int = 0,
        phase: Int = 0,
        phases: Int = 5,
        pairName: String? = "Teste",
    ): SyncStatus.Running = SyncStatus.Running(pairName, step, done, total, phase, phases, NOW)

    private fun result(
        ok: Boolean = true,
        downloaded: Int = 0,
        uploaded: Int = 0,
        failed: Int = 0,
        error: String? = null,
    ): CycleResult = CycleResult(
        finishedAt = LAST,
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
        const val LAST = NOW - 300_000L
    }
}
