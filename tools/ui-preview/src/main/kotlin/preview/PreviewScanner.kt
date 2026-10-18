package preview

/**
 * Pairing by QR code in the previews: what the phone's code scanner reads (the stand-in for
 * `ui/common/CodeScanner.kt` gives [code]) and the host key each address shows ([hostKeys], for
 * `SshConnector.hostKey`; an address not in it doesn't answer). Scenes set them with `scanned` and `reachable`.
 */
object PreviewScanner {
    @Volatile var code: String? = null
    @Volatile var hostKeys: Map<String, String> = emptyMap()
}
