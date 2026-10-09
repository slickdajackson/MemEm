package app.memem.models

import android.app.Notification
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class DownloadNoteTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()

    @Test
    fun doneIsSwipeableWithoutProgress() {
        val note = DownloadNotes.done(context)
        assertEquals(0, note.flags and Notification.FLAG_ONGOING_EVENT)
        assertTrue(note.flags and Notification.FLAG_AUTO_CANCEL != 0)
        assertEquals(0, note.extras.getInt(Notification.EXTRA_PROGRESS))
        assertEquals(0, note.extras.getInt(Notification.EXTRA_PROGRESS_MAX))
        assertEquals("Modelle liegen bereit", note.extras.getCharSequence(Notification.EXTRA_TEXT).toString())
        assertEquals(10_000L, note.timeoutAfter)
    }

    @Test
    fun runningStaysOngoing() {
        val note = DownloadNotes.running(context, "gemma-cpu 2/2: 40%", 40)
        assertTrue(note.flags and Notification.FLAG_ONGOING_EVENT != 0)
        assertEquals(40, note.extras.getInt(Notification.EXTRA_PROGRESS))
        assertEquals(100, note.extras.getInt(Notification.EXTRA_PROGRESS_MAX))
    }

    @Test
    fun failureIsSwipeableWithoutProgress() {
        val note = DownloadNotes.failed(context, "Download fehlgeschlagen")
        assertEquals(0, note.flags and Notification.FLAG_ONGOING_EVENT)
        assertTrue(note.flags and Notification.FLAG_AUTO_CANCEL != 0)
        assertEquals(0, note.extras.getInt(Notification.EXTRA_PROGRESS_MAX))
        assertEquals(1, note.actions.size)
    }
}
