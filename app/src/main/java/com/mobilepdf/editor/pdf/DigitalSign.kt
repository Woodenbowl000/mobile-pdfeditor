package com.mobilepdf.editor.pdf

import com.tom_roush.pdfbox.cos.COSName
import com.tom_roush.pdfbox.pdmodel.interactive.digitalsignature.PDSignature
import com.tom_roush.pdfbox.pdmodel.interactive.digitalsignature.SignatureInterface
import com.tom_roush.pdfbox.pdmodel.interactive.digitalsignature.SignatureOptions
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.math.BigInteger
import java.security.KeyStore
import java.security.MessageDigest
import java.security.PrivateKey
import java.security.Signature
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * Minimal DER (ASN.1) writer and reader: just what a CMS SignedData needs. The app ships without
 * BouncyCastle (it would add megabytes), so the signature container is built and read here.
 */
object Der {
    fun len(n: Int): ByteArray = when {
        n < 0x80 -> byteArrayOf(n.toByte())
        n < 0x100 -> byteArrayOf(0x81.toByte(), n.toByte())
        n < 0x10000 -> byteArrayOf(0x82.toByte(), (n shr 8).toByte(), n.toByte())
        else -> byteArrayOf(0x83.toByte(), (n shr 16).toByte(), (n shr 8).toByte(), n.toByte())
    }

    fun tlv(tag: Int, vararg parts: ByteArray): ByteArray {
        val body = ByteArrayOutputStream().apply { parts.forEach { write(it) } }.toByteArray()
        return byteArrayOf(tag.toByte()) + len(body.size) + body
    }

    fun seq(vararg p: ByteArray) = tlv(0x30, *p)
    /** DER SET OF: elements sorted by their encoding. */
    fun set(vararg p: ByteArray) = tlv(0x31, *p.sortedWith { a, b -> compare(a, b) }.toTypedArray())
    fun int(v: BigInteger) = tlv(0x02, v.toByteArray())
    fun int(v: Int) = int(BigInteger.valueOf(v.toLong()))
    fun octets(b: ByteArray) = tlv(0x04, b)
    val NULL = byteArrayOf(0x05, 0x00)
    fun ctx(n: Int, vararg p: ByteArray) = tlv(0xA0 + n, *p)

    fun oid(s: String): ByteArray {
        val parts = s.split('.').map { it.toLong() }
        val out = ByteArrayOutputStream()
        out.write((parts[0] * 40 + parts[1]).toInt())
        for (v in parts.drop(2)) {
            val stack = ArrayList<Int>()
            var x = v
            stack += (x and 0x7F).toInt(); x = x shr 7
            while (x > 0) { stack += ((x and 0x7F) or 0x80).toInt(); x = x shr 7 }
            stack.reversed().forEach { out.write(it) }
        }
        return tlv(0x06, out.toByteArray())
    }

    fun utcTime(d: Date): ByteArray {
        val f = SimpleDateFormat("yyMMddHHmmss'Z'", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }
        return tlv(0x17, f.format(d).toByteArray(Charsets.US_ASCII))
    }

    private fun compare(a: ByteArray, b: ByteArray): Int {
        for (i in 0 until minOf(a.size, b.size)) {
            val c = (a[i].toInt() and 0xFF) - (b[i].toInt() and 0xFF)
            if (c != 0) return c
        }
        return a.size - b.size
    }

    /** A parsed element: [tag], its full encoding [raw], and its content [value]. */
    class Node(val tag: Int, val raw: ByteArray, val value: ByteArray) {
        val constructed get() = tag and 0x20 != 0
        val children: List<Node> by lazy { if (constructed) parseAll(value) else emptyList() }
        fun oidString(): String {
            val v = value; val sb = StringBuilder()
            val first = v[0].toInt() and 0xFF
            sb.append(first / 40).append('.').append(first % 40)
            var x = 0L
            for (i in 1 until v.size) {
                val b = v[i].toInt() and 0xFF
                x = (x shl 7) or (b and 0x7F).toLong()
                if (b and 0x80 == 0) { sb.append('.').append(x); x = 0 }
            }
            return sb.toString()
        }
    }

    fun parse(b: ByteArray, at: Int = 0): Pair<Node, Int> {
        var i = at
        val tag = b[i++].toInt() and 0xFF
        var n = b[i++].toInt() and 0xFF
        if (n and 0x80 != 0) {
            val k = n and 0x7F
            if (k == 0) throw IllegalArgumentException("indefinite length")
            n = 0; repeat(k) { n = (n shl 8) or (b[i++].toInt() and 0xFF) }
        }
        val value = b.copyOfRange(i, i + n)
        return Node(tag, b.copyOfRange(at, i + n), value) to (i + n)
    }

    fun parseAll(b: ByteArray): List<Node> {
        val out = ArrayList<Node>(); var i = 0
        while (i < b.size) { val (n, next) = parse(b, i); out += n; i = next }
        return out
    }
}

/**
 * Digital (certificate) signatures, after PdfCraft's `pdfcraft-sign`: sign with a key from the Android
 * keystore (made on this phone, or imported from a .p12/.pfx), and check the signatures a PDF already has.
 * The signature is a CMS SignedData (adbe.pkcs7.detached) added as an incremental update, so the bytes
 * that were signed stay exactly as they were.
 */
object DigitalSign {
    private const val OID_DATA = "1.2.840.113549.1.7.1"
    private const val OID_SIGNED_DATA = "1.2.840.113549.1.7.2"
    private const val OID_CONTENT_TYPE = "1.2.840.113549.1.9.3"
    private const val OID_MESSAGE_DIGEST = "1.2.840.113549.1.9.4"
    private const val OID_SIGNING_TIME = "1.2.840.113549.1.9.5"
    private const val OID_SHA256 = "2.16.840.1.101.3.4.2.1"
    private const val OID_RSA = "1.2.840.113549.1.1.1"
    private const val OID_ECDSA_SHA256 = "1.2.840.10045.4.3.2"
    const val ALIAS_OWN = "pdfeditor-own"
    const val ALIAS_IMPORTED = "pdfeditor-imported"

    class Signer(val key: PrivateKey, val chain: List<X509Certificate>) {
        val name: String get() = commonName(chain.first())
    }

    fun commonName(c: X509Certificate): String =
        Regex("CN=([^,]+)").find(c.subjectX500Principal.name)?.groupValues?.get(1)?.trim() ?: c.subjectX500Principal.name

    // ------------------------------------------------------------------------------------ identities

    private fun keystore() = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }

    /** The signing identity stored on this phone, if any (an imported certificate wins over the own one). */
    fun current(): Signer? = runCatching {
        val ks = keystore()
        for (alias in listOf(ALIAS_IMPORTED, ALIAS_OWN)) {
            if (!ks.containsAlias(alias)) continue
            val e = ks.getEntry(alias, null) as? KeyStore.PrivateKeyEntry ?: continue
            return Signer(e.privateKey, e.certificateChain.filterIsInstance<X509Certificate>())
        }
        null
    }.getOrNull()

    /**
     * A key made on this phone (it never leaves the secure keystore) with a self-signed certificate in
     * [name]. Readers see who signed and that nothing changed; they can't see a trusted authority vouch for it.
     */
    fun createOwn(name: String): Signer {
        val ks = keystore()
        ks.deleteEntry(ALIAS_OWN)
        val spec = android.security.keystore.KeyGenParameterSpec.Builder(ALIAS_OWN, android.security.keystore.KeyProperties.PURPOSE_SIGN)
            .setDigests(android.security.keystore.KeyProperties.DIGEST_SHA256)
            .setSignaturePaddings(android.security.keystore.KeyProperties.SIGNATURE_PADDING_RSA_PKCS1)
            .setKeySize(2048)
            .setCertificateSubject(javax.security.auth.x500.X500Principal("CN=${name.replace(",", " ").replace("=", " ").trim()}"))
            .setCertificateSerialNumber(BigInteger.valueOf(System.currentTimeMillis()))
            .setCertificateNotBefore(Date(System.currentTimeMillis() - 86_400_000L))
            .setCertificateNotAfter(Date(System.currentTimeMillis() + 10L * 365 * 86_400_000L))
            .build()
        java.security.KeyPairGenerator.getInstance(android.security.keystore.KeyProperties.KEY_ALGORITHM_RSA, "AndroidKeyStore").apply {
            initialize(spec); generateKeyPair()
        }
        ks.deleteEntry(ALIAS_IMPORTED)
        return current() ?: throw IllegalStateException("The signing key could not be created")
    }

    /** Imports a certificate and its private key from a PKCS#12 file into the keystore. */
    fun importP12(data: InputStream, password: CharArray): Signer {
        val p12 = KeyStore.getInstance("PKCS12").apply { load(data, password) }
        val alias = p12.aliases().toList().firstOrNull { p12.isKeyEntry(it) } ?: throw IllegalArgumentException("No private key in this file")
        val key = p12.getKey(alias, password) as PrivateKey
        val chain = p12.getCertificateChain(alias)
        val ks = keystore()
        ks.setEntry(
            ALIAS_IMPORTED, KeyStore.PrivateKeyEntry(key, chain),
            android.security.keystore.KeyProtection.Builder(android.security.keystore.KeyProperties.PURPOSE_SIGN)
                .setDigests(android.security.keystore.KeyProperties.DIGEST_SHA256)
                .apply { if (key.algorithm.equals("RSA", true)) setSignaturePaddings(android.security.keystore.KeyProperties.SIGNATURE_PADDING_RSA_PKCS1) }
                .build(),
        )
        return current() ?: throw IllegalStateException("The certificate could not be stored")
    }

    fun forget() { runCatching { keystore().apply { deleteEntry(ALIAS_OWN); deleteEntry(ALIAS_IMPORTED) } } }

    // ---------------------------------------------------------------------------------------- signing

    /** CMS SignedData over [content] (detached): what goes into the signature's /Contents. */
    fun cms(content: ByteArray, s: Signer, time: Date = Date()): ByteArray {
        val cert = s.chain.first()
        val digest = MessageDigest.getInstance("SHA-256").digest(content)
        val attrs = listOf(
            Der.seq(Der.oid(OID_CONTENT_TYPE), Der.set(Der.oid(OID_DATA))),
            Der.seq(Der.oid(OID_SIGNING_TIME), Der.set(Der.utcTime(time))),
            Der.seq(Der.oid(OID_MESSAGE_DIGEST), Der.set(Der.octets(digest))),
        )
        val signedAttrsSet = Der.set(*attrs.toTypedArray())
        val ec = s.key.algorithm.equals("EC", ignoreCase = true)
        val sig = Signature.getInstance(if (ec) "SHA256withECDSA" else "SHA256withRSA").run {
            initSign(s.key); update(signedAttrsSet); sign()
        }
        // [0] IMPLICIT: the same bytes as the SET, with the context tag
        val signedAttrs = byteArrayOf(0xA0.toByte()) + signedAttrsSet.copyOfRange(1, signedAttrsSet.size)
        val issuerSerial = Der.seq(cert.issuerX500Principal.encoded, Der.int(cert.serialNumber))
        val digestAlg = Der.seq(Der.oid(OID_SHA256), Der.NULL)
        val sigAlg = if (ec) Der.seq(Der.oid(OID_ECDSA_SHA256)) else Der.seq(Der.oid(OID_RSA), Der.NULL)
        val signerInfo = Der.seq(Der.int(1), issuerSerial, digestAlg, signedAttrs, sigAlg, Der.octets(sig))
        val certs = Der.ctx(0, *s.chain.map { it.encoded }.toTypedArray())
        val signedData = Der.seq(Der.int(1), Der.set(digestAlg), Der.seq(Der.oid(OID_DATA)), certs, Der.set(signerInfo))
        return Der.seq(Der.oid(OID_SIGNED_DATA), Der.ctx(0, signedData))
    }

    /** Signs [src] into [out] as an incremental update. */
    fun sign(src: File, out: File, s: Signer, reason: String?, location: String?) {
        PdfOps.load(src).use { d ->
            val sig = PDSignature()
            sig.setFilter(PDSignature.FILTER_ADOBE_PPKLITE)
            sig.setSubFilter(PDSignature.SUBFILTER_ADBE_PKCS7_DETACHED)
            sig.name = s.name
            reason?.takeIf { it.isNotBlank() }?.let { sig.reason = it }
            location?.takeIf { it.isNotBlank() }?.let { sig.location = it }
            sig.signDate = Calendar.getInstance()
            val opts = SignatureOptions().apply { preferredSignatureSize = 18_000 + s.chain.sumOf { it.encoded.size } * 2 }
            d.addSignature(sig, SignatureInterface { content -> cms(content.readBytes(), s) }, opts)
            out.outputStream().use { d.saveIncremental(it) }
            opts.close()
        }
    }

    // ----------------------------------------------------------------------------------- verifying

    enum class Status { VALID, MODIFIED_AFTER, INVALID, UNSUPPORTED }

    data class Check(
        val signer: String, val date: Date?, val reason: String?, val status: Status,
        val selfSigned: Boolean, val issuer: String?, val detail: String,
    )

    /** Cheap pre-check (no parsing): only a file with a signature's /ByteRange can be signed. */
    fun mightBeSigned(file: File): Boolean {
        val key = "/ByteRange".toByteArray(Charsets.US_ASCII)
        file.inputStream().buffered(1 shl 16).use { input ->
            val buf = ByteArray(1 shl 16); var carry = ByteArray(0)
            while (true) {
                val n = input.read(buf); if (n <= 0) return false
                val chunk = carry + buf.copyOf(n)
                outer@ for (i in 0..chunk.size - key.size) {
                    for (k in key.indices) if (chunk[i + k] != key[k]) continue@outer
                    return true
                }
                carry = chunk.copyOfRange(maxOf(0, chunk.size - key.size + 1), chunk.size)
            }
        }
    }

    /** Every signature in [file], each checked: is the signed content intact, and does it cover the whole file? */
    fun verify(file: File): List<Check> {
        return PdfOps.load(file).use { d ->
            val sigs = d.signatureDictionaries
            if (sigs.isEmpty()) return@use emptyList()
            val bytes = file.readBytes() // only signed documents are read whole
            sigs.map { sig -> runCatching { check(sig, bytes) }.getOrElse { e ->
                Check(sig.name ?: "Unknown", sig.signDate?.time, sig.reason, Status.INVALID, false, null, "Could not be read: ${e.message}")
            } }
        }
    }

    private fun check(sig: PDSignature, file: ByteArray): Check {
        val name = sig.name
        val sub = sig.subFilter ?: ""
        if (sub != PDSignature.SUBFILTER_ADBE_PKCS7_DETACHED.name && sub != "ETSI.CAdES.detached") {
            return Check(name ?: "Unknown", sig.signDate?.time, sig.reason, Status.UNSUPPORTED, false, null, "Signature type $sub can't be checked here")
        }
        val br = sig.byteRange
        val content = ByteArrayOutputStream().apply {
            write(file, br[0], br[1]); write(file, br[2], br[3])
        }.toByteArray()
        val cmsBytes = sig.getContents(file)
        val ci = Der.parse(cmsBytes).first
        val sd = ci.children[1].children[0] // [0] EXPLICIT SignedData
        val parts = sd.children
        val certsNode = parts.firstOrNull { it.tag == 0xA0 }
        val certs = certsNode?.let {
            CertificateFactory.getInstance("X.509").generateCertificates(ByteArrayInputStream(it.value)).filterIsInstance<X509Certificate>()
        }.orEmpty()
        val si = parts.last().children.first()
        val sif = si.children
        val sid = sif[1]
        val serial = BigInteger(sid.children.getOrNull(1)?.value ?: byteArrayOf(0))
        val cert = certs.firstOrNull { it.serialNumber == serial } ?: certs.firstOrNull()
            ?: return Check(name ?: "Unknown", sig.signDate?.time, sig.reason, Status.INVALID, false, null, "No certificate in the signature")
        val digestOid = sif[2].children[0].oidString()
        val mdName = when (digestOid) {
            "2.16.840.1.101.3.4.2.1" -> "SHA-256"; "2.16.840.1.101.3.4.2.2" -> "SHA-384"; "2.16.840.1.101.3.4.2.3" -> "SHA-512"; "1.3.14.3.2.26" -> "SHA-1"
            else -> return Check(commonName(cert), sig.signDate?.time, sig.reason, Status.UNSUPPORTED, false, null, "Digest $digestOid not supported")
        }
        val attrs = sif.firstOrNull { it.tag == 0xA0 }
        val sigAlgOid = sif[sif.size - 2].children[0].oidString()
        val sigValue = sif.last().value
        val digest = MessageDigest.getInstance(mdName).digest(content)
        val ok: Boolean
        if (attrs != null) {
            val md = attrs.children.firstOrNull { it.children[0].oidString() == OID_MESSAGE_DIGEST }?.children?.get(1)?.children?.get(0)?.value
            val setBytes = byteArrayOf(0x31) + attrs.raw.copyOfRange(1, attrs.raw.size)
            ok = md != null && md.contentEquals(digest) && verifySig(cert, mdName, sigAlgOid, setBytes, sigValue)
        } else {
            ok = verifySig(cert, mdName, sigAlgOid, content, sigValue)
        }
        val whole = br[2] + br[3] == file.size
        val selfSigned = cert.issuerX500Principal == cert.subjectX500Principal
        val status = when { !ok -> Status.INVALID; !whole -> Status.MODIFIED_AFTER; else -> Status.VALID }
        val detail = when (status) {
            Status.VALID -> "The document has not been changed since it was signed"
            Status.MODIFIED_AFTER -> "The signed version is intact, but the document was changed after signing"
            Status.INVALID -> "The document was altered: the signature doesn't match its content"
            Status.UNSUPPORTED -> ""
        }
        return Check(commonName(cert), sig.signDate?.time, sig.reason, status, selfSigned, commonName(certs.firstOrNull { it.subjectX500Principal == cert.issuerX500Principal } ?: cert).takeIf { !selfSigned } ?: cert.issuerX500Principal.name, detail)
    }

    private fun verifySig(cert: X509Certificate, md: String, algOid: String, data: ByteArray, sig: ByteArray): Boolean = runCatching {
        val key = cert.publicKey
        val alg = when {
            algOid == OID_ECDSA_SHA256 || key.algorithm.equals("EC", true) -> md.replace("-", "") + "withECDSA"
            else -> md.replace("-", "") + "withRSA"
        }
        Signature.getInstance(alg).run { initVerify(key); update(data); verify(sig) }
    }.getOrDefault(false)

    @Suppress("unused") private val keep = COSName.SIG
}
