package hk.uwu.reareye.agent.data

import android.content.ContentProvider
import android.content.ContentValues
import android.content.pm.ProviderInfo
import android.database.MatrixCursor
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.OpenableColumns
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowContentResolver
import java.io.File
import java.util.Base64

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [35])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class SessionWorkspaceUriTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test fun importsChosenContentUriAsPrivateCopyAndNeverUsesItsExternalPathForPreview() {
        val app = RuntimeEnvironment.getApplication()
        val authority = "test.mary.attachment"
        val png = Base64.getDecoder().decode("iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+ip1sAAAAASUVORK5CYII=")
        val source = temporary.newFile("outside.png").also { it.writeBytes(png) }
        val provider = object : ContentProvider() {
            override fun onCreate() = true
            override fun getType(uri: Uri) = "image/png"
            override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor =
                ParcelFileDescriptor.open(source, ParcelFileDescriptor.MODE_READ_ONLY)
            override fun query(uri: Uri, projection: Array<out String>?, selection: String?,
                               selectionArgs: Array<out String>?, sortOrder: String?) = MatrixCursor(
                arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE)
            ).apply { addRow(arrayOf<Any>("../from-picker.png", source.length())) }
            override fun insert(uri: Uri, values: ContentValues?): Uri? = null
            override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?) = 0
            override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?) = 0
        }
        provider.attachInfo(app, ProviderInfo().apply { this.authority = authority; exported = true })
        ShadowContentResolver.registerProviderInternal(authority, provider)
        val files = temporary.newFolder("private-files")
        val store = robolectricWorkspaceStore(files)
        val session = store.create()
        val uri = Uri.parse("content://$authority/document/external-token")
        val ref = store.workspace.importUri(session.id, app.contentResolver, uri)
        assertEquals(".._from-picker.png", ref.name)
        assertEquals(png.size.toLong(), ref.size)
        source.writeText("source changed after import")
        val preview = store.workspace.previewUri(session.id, ref)!!
        assertEquals("file", preview.scheme)
        val privateCopy = File(preview.path!!)
        assertTrue(privateCopy.canonicalPath.startsWith(files.canonicalPath + File.separator))
        assertFalse(privateCopy.canonicalPath == source.canonicalPath)
        assertTrue(store.workspace.readBytes(session.id, ref).contentEquals(png))
        assertThrows(IllegalArgumentException::class.java) {
            store.workspace.importUri(session.id, app.contentResolver, Uri.fromFile(source))
        }
        store.workspace.discardDraft(session.id, ref.id)
        assertFalse(privateCopy.exists())
    }
}
