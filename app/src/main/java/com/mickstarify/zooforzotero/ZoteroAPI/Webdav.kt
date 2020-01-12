package com.mickstarify.zooforzotero.ZoteroAPI

import android.content.Context
import android.util.Log
import com.mickstarify.zooforzotero.ZoteroStorage.AttachmentStorageManager
import com.mickstarify.zooforzotero.ZoteroStorage.Database.Item
import com.thegrizzlylabs.sardineandroid.impl.OkHttpSardine
import io.reactivex.Completable
import io.reactivex.Observable
import io.reactivex.Single
import io.reactivex.SingleEmitter
import io.reactivex.functions.Action
import net.lingala.zip4j.ZipFile
import okio.Okio
import okio.buffer
import okio.sink
import okio.source
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStreamWriter
import java.util.*

class Webdav(
    address: String,
    val username: String,
    val password: String
) {
    var sardine: OkHttpSardine
    var address: String
    fun testConnection(): Boolean {
        return sardine.exists(address)
    }

    fun downloadPropToString(webpath: String): String {
        val reader = sardine.get(webpath).source()
        val bufferedReader = reader.buffer()
        val s = bufferedReader.readUtf8()
        bufferedReader.close()
        return s
    }

    fun downloadFileRx(
        attachment: Item,
        context: Context,
        attachmentStorageManager: AttachmentStorageManager
    ): Observable<DownloadProgress> {
        val webpathProp = address + "/${attachment.itemKey.toUpperCase(Locale.ROOT)}.prop"
        val webpathZip = address + "/${attachment.itemKey.toUpperCase(Locale.ROOT)}.zip"

        val observable: Observable<DownloadProgress> = Observable.create { emitter ->
            try {
                val propString = downloadPropToString(webpathProp)
                val prop = WebdavProp(propString)

                var inputStream: InputStream? = null
                try {
                    inputStream = sardine.get(webpathZip)
                } catch (e: IllegalArgumentException) {
                    Log.e("zotero", "${e}")
                    throw(e)
                } catch (e: Exception) {
                    Log.e("zotero", "${e}")
                    throw(e)
                }

                val zipFile =
                    attachmentStorageManager.createTempFile(
                        "${attachment.itemKey.toUpperCase(Locale.ROOT)}.pdf"
                    )
                val downloadOutputStream = zipFile.outputStream()

                val buffer = ByteArray(32768)
                var read = inputStream.read(buffer)
                var total: Long = 0
                while (read > 0) {
                    try {
                        total += read
                        emitter.onNext(DownloadProgress(total, -1, prop.mtime, prop.hash))
                        downloadOutputStream.write(buffer, 0, read)
                        read = inputStream.read(buffer)
                    } catch (e: Exception) {
                        Log.e("zotero", "exception downloading webdav attachment ${e.message}")
                        throw RuntimeException("Error downloading webdav attachment ${e.message}")
                    }
                }
                downloadOutputStream.close()
                inputStream.close()
                if (read > 0) {
                    throw RuntimeException(
                        "Error did not finish downloading ${attachment.itemKey.toUpperCase(
                            Locale.ROOT
                        )}.zip"
                    )
                }
                val zipFile2 = ZipFile(zipFile)
                val attachmentFilename =
                    zipFile2.fileHeaders.firstOrNull()?.fileName
                        ?: throw Exception("Error empty zipfile.")
                ZipFile(zipFile).extractAll(context.cacheDir.absolutePath)
                zipFile.delete() // don't need this anymore.
                attachmentStorageManager.writeAttachmentFromFile(
                    File(
                        context.cacheDir,
                        attachmentFilename
                    ), attachment
                )
                File(context.cacheDir, attachmentFilename).delete()
                emitter.onComplete()
            } catch (e: Exception) {
                Log.e("zotero", "big exception hit ${e} || ${emitter.isDisposed}")
                // this your brain on rxjava. this is to avoid a undeliverable crash on dispose().
                if (!emitter.isDisposed) {
                    throw e
                }
            }
        }
        return observable
    }

    fun uploadAttachment(
        attachment: Item,
        attachmentStorageManager: AttachmentStorageManager
    ): Completable {
        /*Uploading will take 3 steps,
        * 1. Compress attachment into a ZIP file (using internal cache dir)
        * 2. Create  F3FXJF_NEW.prop file.
        * 3. Upload to webdav server F3FXJF_NEW.zip and F3FXJF_NEW.prop
        * 4. send a delete request and  rename request to server so we have
        *  F3FXJF.zip + F3FXJF.prop resulting*/

        return Completable.fromAction({
            // STEP 1 CREATE ZIP
            val fileInputStream =
                attachmentStorageManager.getItemInputStream(attachment).source()
            val filename = attachmentStorageManager.getFilenameForItem(attachment)
            val tempFile = attachmentStorageManager.createTempFile(filename)
            val sinkBuffer = tempFile.outputStream().sink().buffer()
            sinkBuffer.writeAll(fileInputStream)
            sinkBuffer.close()
            fileInputStream.close()

            val zipFile =
                attachmentStorageManager.createTempFile("${attachment.itemKey.toUpperCase(Locale.ROOT)}_NEW.zip")

            ZipFile(zipFile).addFile(tempFile)

            // STEP 2 -- CREATE PROP
            val propFile =
                attachmentStorageManager.createTempFile("${attachment.itemKey.toUpperCase(Locale.ROOT)}_NEW.prop")
            val propContent = WebdavProp(
                attachmentStorageManager.getMtime(attachment),
                attachmentStorageManager.calculateMd5(attachment)
            ).serialize()
            val outputStream = propFile.outputStream()
            outputStream.write(propContent.toByteArray(Charsets.UTF_8))
            outputStream.close()

            // STEP 3 -- UPLOAD ZIP AND PROP
            val newZipPath = address + "/${attachment.itemKey.toUpperCase(Locale.ROOT)}_NEW.zip"
            val newPropPath = address + "/${attachment.itemKey.toUpperCase(Locale.ROOT)}_NEW.prop"
            sardine.put(newPropPath, propFile, "text/plain")
            sardine.put(newZipPath, zipFile, "application/zip")

            // STEP 4 -- DELETE AND RENAME TO REPLACE OLD CONTENT.
            val zipPath = address + "/${attachment.itemKey.toUpperCase(Locale.ROOT)}.zip"
            val propPath = address + "/${attachment.itemKey.toUpperCase(Locale.ROOT)}.prop"

            sardine.delete(propPath)
            sardine.delete(zipPath)

            sardine.move(newPropPath, propPath)
            sardine.move(newZipPath, zipPath)

            // DONE.
        })

    }

    init {
        sardine = OkHttpSardine()
        sardine.allowForInsecureSSL()
        if (username != "" && password != "") {
            sardine.setCredentials(username, password)
        }

        this.address = if (address.endsWith("/zotero")) {
            address
        } else {
            if (address.endsWith("/")) { // so we don't get server.com//zotero
                address + "zotero"
            } else {
                address + "/zotero"
            }
        }
    }

}