package dev.welles.notes.android

import android.content.Context
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.codescanner.GmsBarcodeScannerOptions
import com.google.mlkit.vision.codescanner.GmsBarcodeScanning
import dev.welles.notes.core.sync.QrScanner
import kotlin.coroutines.resume
import kotlinx.coroutines.suspendCancellableCoroutine

/** Google code scanner (Play services): no camera permission needed. Without Play services the JSON paste remains. */
class GmsQrScanner(private val context: Context) : QrScanner {
    override suspend fun scan(): String? = suspendCancellableCoroutine { cont ->
        val options = GmsBarcodeScannerOptions.Builder().setBarcodeFormats(Barcode.FORMAT_QR_CODE).build()
        GmsBarcodeScanning.getClient(context, options).startScan()
            .addOnSuccessListener { cont.resume(it.rawValue) }
            .addOnCanceledListener { cont.resume(null) }
            .addOnFailureListener { cont.resume(null) }
    }
}
