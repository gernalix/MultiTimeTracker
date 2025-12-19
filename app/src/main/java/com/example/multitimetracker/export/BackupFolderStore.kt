// v1
package com.example.multitimetracker.export

import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile

/**
 * Persists the user-selected SAF tree URI and exposes a stable subfolder:
 *   <picked root>/MultiTimer data/
 *
 * This folder survives uninstall/reinstall (as long as the user keeps the files),
 * and import/export can run without asking the user to pick files every time.
 */
object BackupFolderStore {

    private const val PREFS = "multitimetracker_backup"
    private const val KEY_TREE_URI = "tree_uri"
    private const val DATA_DIR_NAME = "MultiTimer data"

    fun getTreeUri(context: Context): Uri? {
        val s = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_TREE_URI, null)
            ?: return null
        return runCatching { Uri.parse(s) }.getOrNull()
    }

    fun saveTreeUri(context: Context, treeUri: Uri) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_TREE_URI, treeUri.toString())
            .apply()
    }

    /**
     * Returns the persistent data directory (creating it if needed).
     */
    fun getOrCreateDataDir(context: Context): DocumentFile {
        val treeUri = getTreeUri(context)
            ?: throw IllegalStateException("Cartella backup non configurata")

        val root = DocumentFile.fromTreeUri(context, treeUri)
            ?: throw IllegalStateException("Impossibile accedere alla cartella backup")

        // Create/find the stable subfolder.
        val existing = root.findFile(DATA_DIR_NAME)
        if (existing != null && existing.isDirectory) return existing

        return root.createDirectory(DATA_DIR_NAME)
            ?: throw IllegalStateException("Impossibile creare cartella '$DATA_DIR_NAME'")
    }
}
