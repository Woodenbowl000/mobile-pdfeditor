package com.mobilepdf.editor.ui.viewer

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import com.mobilepdf.editor.ui.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.mobilepdf.editor.pdf.DigitalSign
import java.text.DateFormat

/**
 * Digital signature: the signing identity (a key made on this phone, or an imported certificate), an
 * optional reason and place, then Sign. Signing is the last step: any change afterwards breaks it.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DigitalSignSheet(
    signer: DigitalSign.Signer?, onDismiss: () -> Unit,
    onCreate: (String) -> Unit, onImport: () -> Unit, onForget: () -> Unit,
    onSign: (reason: String, location: String) -> Unit,
) {
    var name by remember { mutableStateOf("") }
    var reason by remember { mutableStateOf("") }
    var location by remember { mutableStateOf("") }
    ModalBottomSheet(onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.verticalScroll(rememberScrollState()).padding(horizontal = 20.dp).padding(bottom = 16.dp).imePadding().navigationBarsPadding()) {
            Text("Digital signature", style = MaterialTheme.typography.titleLarge)
            Text(
                "Proves who signed and that nothing has changed since. Sign last: any change after signing breaks it.",
                style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            HorizontalDivider(Modifier.padding(vertical = 12.dp))
            if (signer == null) {
                Text("Set up your signing identity", style = MaterialTheme.typography.titleSmall)
                OutlinedTextField(name, { name = it.take(64) }, label = { Text("Your full name") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                Button({ onCreate(name.trim()) }, enabled = name.isNotBlank(), modifier = Modifier.fillMaxWidth().padding(top = 8.dp)) {
                    Text("Create my signing key")
                }
                Text(
                    "The key is made and kept in this phone's secure storage; it never leaves it. Readers will see your name; " +
                        "no authority vouches for it, so their app may call the identity \"unknown\".",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                OutlinedButton(onImport, Modifier.fillMaxWidth().padding(top = 12.dp)) { Text("Import a certificate (.p12 / .pfx)") }
                Text(
                    "Use one issued to you by a certificate authority or your organisation.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                val cert = signer.chain.first()
                val self = cert.issuerX500Principal == cert.subjectX500Principal
                Text("Signing as", style = MaterialTheme.typography.labelLarge)
                Text(signer.name, style = MaterialTheme.typography.titleMedium)
                Text(
                    if (self) "Key made on this phone" else "Certificate issued by ${DigitalSign.commonName(signer.chain.getOrNull(1) ?: cert).takeIf { signer.chain.size > 1 } ?: cert.issuerX500Principal.name}",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                TextButton(onForget) { Text("Use a different identity") }
                OutlinedTextField(reason, { reason = it.take(120) }, label = { Text("Reason (optional)") }, placeholder = { Text("I approve this document") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(location, { location = it.take(80) }, label = { Text("Place (optional)") }, singleLine = true, modifier = Modifier.fillMaxWidth().padding(top = 6.dp))
                Button({ onSign(reason.trim(), location.trim()) }, Modifier.fillMaxWidth().padding(top = 12.dp)) { Text("Sign document") }
            }
        }
    }
}

/** Password for an imported certificate file. */
@Composable
fun CertPasswordDialog(onDismiss: () -> Unit, onDone: (CharArray) -> Unit) {
    var pw by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Certificate password") },
        text = {
            OutlinedTextField(
                pw, { pw = it }, label = { Text("Password") }, singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password), modifier = Modifier.fillMaxWidth(),
            )
        },
        confirmButton = { TextButton({ onDone(pw.toCharArray()) }) { Text("Import") } },
        dismissButton = { TextButton(onDismiss) { Text("Cancel") } },
    )
}

fun statusColor(s: DigitalSign.Status) = when (s) {
    DigitalSign.Status.VALID -> Color(0xFF2E7D32)
    DigitalSign.Status.MODIFIED_AFTER -> Color(0xFFEF6C00)
    DigitalSign.Status.INVALID -> Color(0xFFC62828)
    DigitalSign.Status.UNSUPPORTED -> Color(0xFF757575)
}

fun statusLabel(s: DigitalSign.Status) = when (s) {
    DigitalSign.Status.VALID -> "Valid"
    DigitalSign.Status.MODIFIED_AFTER -> "Valid, but changed after signing"
    DigitalSign.Status.INVALID -> "Invalid: altered"
    DigitalSign.Status.UNSUPPORTED -> "Can't be checked"
}

/** Every signature in the document and what checking it found (PdfCraft's Signatures panel). */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SignaturesSheet(checks: List<DigitalSign.Check>, onDismiss: () -> Unit) {
    val df = DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT)
    ModalBottomSheet(onDismiss) {
        Column(Modifier.padding(horizontal = 20.dp).padding(bottom = 16.dp).navigationBarsPadding()) {
            Text("Signatures (${checks.size})", style = MaterialTheme.typography.titleLarge)
            checks.forEach { c ->
                HorizontalDivider(Modifier.padding(vertical = 10.dp))
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Box(Modifier.size(14.dp).background(statusColor(c.status), CircleShape))
                    Text(statusLabel(c.status), style = MaterialTheme.typography.titleSmall, color = statusColor(c.status))
                }
                Text("Signed by ${c.signer}", style = MaterialTheme.typography.bodyLarge)
                c.date?.let { Text(df.format(it), style = MaterialTheme.typography.bodySmall) }
                c.reason?.takeIf { it.isNotBlank() }?.let { Text("Reason: $it", style = MaterialTheme.typography.bodySmall) }
                Text(c.detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(
                    if (c.selfSigned) "Identity: self-issued (not vouched for by an authority)" else "Certificate issued by ${c.issuer ?: "unknown"} (not checked against a trust list)",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}
