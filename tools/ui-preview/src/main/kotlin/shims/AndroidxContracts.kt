@file:Suppress("unused", "UNUSED_PARAMETER")

package androidx.activity.result.contract

import android.net.Uri

abstract class ActivityResultContract<I, O>

class ActivityResultContracts {
    class OpenDocument : ActivityResultContract<Array<String>, Uri?>()
    class CreateDocument(mimeType: String) : ActivityResultContract<String, Uri?>()
    class GetMultipleContents : ActivityResultContract<String, List<Uri>>()
}
