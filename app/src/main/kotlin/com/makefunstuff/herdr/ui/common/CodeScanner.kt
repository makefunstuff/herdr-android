package com.makefunstuff.herdr.ui.common

import android.content.Context
import com.google.android.gms.common.api.ApiException
import com.google.mlkit.common.MlKitException
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.codescanner.GmsBarcodeScannerOptions
import com.google.mlkit.vision.codescanner.GmsBarcodeScanning
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** Why the phone's code scanner couldn't open, said for the person holding it. */
class ScannerUnavailable(message: String, cause: Throwable? = null) : Exception(message, cause)

/**
 * Opens the phone's own QR code scanner (Google Play services' code scanner: its own screen, so the
 * app needs no camera permission) and gives what it read; null when it was closed without a code.
 *
 * @throws ScannerUnavailable when the phone has no Play services, or their scanner isn't installed yet.
 */
suspend fun scanCode(context: Context): String? {
    val options = GmsBarcodeScannerOptions.Builder().setBarcodeFormats(Barcode.FORMAT_QR_CODE).build()
    val scanner = runCatching { GmsBarcodeScanning.getClient(context, options) }
        .getOrElse { throw ScannerUnavailable(NO_SCANNER, it) }
    return suspendCancellableCoroutine { cont ->
        scanner.startScan()
            .addOnSuccessListener { cont.resume(it.rawValue) }
            .addOnCanceledListener { cont.resume(null) }
            .addOnFailureListener { e ->
                val message = when {
                    e is MlKitException && e.errorCode == MlKitException.UNAVAILABLE ->
                        "Google Play services is still installing its code scanner. Try again in a minute, or paste the link."
                    e is ApiException || e is MlKitException -> NO_SCANNER
                    else -> e.message ?: NO_SCANNER
                }
                cont.resumeWithException(ScannerUnavailable(message, e))
            }
    }
}

private const val NO_SCANNER = "This phone has no code scanner (it comes with Google Play services). Paste the link herdr-push pair prints instead."
