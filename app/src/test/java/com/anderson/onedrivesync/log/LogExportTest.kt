package com.anderson.onedrivesync.log

import com.anderson.onedrivesync.db.LogRow
import com.anderson.onedrivesync.hyperos.HyperOsPermissions
import com.anderson.onedrivesync.sync.SyncState
import java.time.Instant
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Format of the exported header/body: the only thing a user without ADB can send to support. */
class LogExportTest {
    private val zone = ZoneId.of("America/Sao_Paulo")
    private val checklist = HyperOsPermissions.Checklist(
        allFiles = true,
        notifications = true,
        battery = false,
        autostart = true,
    )

    private fun header(
        signedIn: Boolean = true,
        clientId: String? = "12345678-aaaa-bbbb-cccc-1234abcd",
    ): String = buildHeader(
        nowMs = Instant.parse("2026-11-03T16:45:00Z").toEpochMilli(),
        zone = zone,
        versionName = "1.2.3",
        versionCode = 10203,
        manufacturer = "Xiaomi",
        model = "2201117TY",
        sdkInt = 33,
        signedIn = signedIn,
        clientId = clientId,
        checklist = checklist,
        pairCount = 2,
        pendingCount = 1,
        conflictCount = 3,
        syncState = "Idle",
    )

    @Test
    fun `header carries build version and code`() {
        assertTrue(header().contains("Versão: 1.2.3 (código 10203)"))
    }

    @Test
    fun `header carries device model and sdk`() {
        assertTrue(header().contains("Aparelho: Xiaomi 2201117TY · SDK 33"))
    }

    @Test
    fun `header formats the timestamp with the local offset`() {
        assertTrue(header().contains("Data: 2026-11-03 13:45:00 -03:00"))
    }

    @Test
    fun `header states the account without exposing anything secret`() {
        assertTrue(header().contains("Conta: conectada"))
        assertTrue(header(signedIn = false).contains("Conta: não conectada"))
    }

    @Test
    fun `client id is masked down to the last four characters`() {
        assertTrue(header().contains("Client ID: ****abcd"))
        assertFalse(header().contains("12345678-aaaa"))
    }

    @Test
    fun `maskClientId handles short and missing ids`() {
        assertEquals("****", maskClientId("abc"))
        assertEquals("(não configurado)", maskClientId(null))
        assertEquals("(não configurado)", maskClientId("   "))
    }

    @Test
    fun `header lists the permission checklist`() {
        assertTrue(
            header().contains("Permissões: arquivos=sim, notificações=sim, bateria=não, autostart=sim"),
        )
    }

    @Test
    fun `header lists pairs pending conflicts and log state`() {
        val header = header()
        assertTrue(header.contains("Pares: 2"))
        assertTrue(header.contains("Pendências: 1 · conflitos: 3"))
        assertTrue(header.contains("Estado do sync: Idle"))
    }

    @Test
    fun `permissions text renders every item`() {
        val text = permissionsText(
            HyperOsPermissions.Checklist(allFiles = false, notifications = false, battery = true, autostart = true),
        )
        assertEquals("arquivos=não, notificações=não, bateria=sim, autostart=sim", text)
    }

    @Test
    fun `log line is timestamp level tag and message`() {
        val row = LogRow(
            ts = Instant.parse("2026-11-03T16:45:00.123Z").toEpochMilli(),
            level = "WARN",
            tag = "SyncEngine",
            msg = "delta expirou (410 Gone): reenumerando do zero",
        )

        assertEquals(
            "2026-11-03 13:45:00.123 WARN [SyncEngine] delta expirou (410 Gone): reenumerando do zero",
            formatLogLine(row, zone),
        )
    }

    @Test
    fun `sync state renders idle scanning and running progress`() {
        assertEquals("Idle", syncStateText(SyncState.Idle))
        assertEquals("Scanning", syncStateText(SyncState.Scanning))
        assertEquals(
            "Running(Docs: baixar a.txt 2/5)",
            syncStateText(SyncState.Running("Docs", "baixar a.txt", done = 2, total = 5)),
        )
    }
}
