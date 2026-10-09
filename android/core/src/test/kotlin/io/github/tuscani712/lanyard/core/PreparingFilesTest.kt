package io.github.tuscani712.lanyard.core

import kotlin.test.Test
import kotlin.test.assertEquals

class PreparingFilesTest {
    private fun row(id: String, fp: String, state: TransferState, files: Int) =
        TransferRecord(id, "send", "Desk", fp, "$files file(s)", 0, 0, state, null, 0.0, 0L, fileCount = files)

    @Test
    fun aBatchCountsItsFilesNotItsRow() {
        val counts = preparingFilesByPeer(listOf(row("a", "AB12", TransferState.Preparing, 5)))
        assertEquals(mapOf("ab12" to 5), counts)
    }

    @Test
    fun onlyPreparingRowsCountAndUnknownCountsAsOne() {
        val counts = preparingFilesByPeer(
            listOf(
                row("a", "ab12", TransferState.Preparing, 3),
                row("b", "ab12", TransferState.Preparing, 0),
                row("c", "ab12", TransferState.Running, 9),
                row("d", "cd34", TransferState.Done, 2),
            ),
        )
        assertEquals(mapOf("ab12" to 4), counts)
    }
}
