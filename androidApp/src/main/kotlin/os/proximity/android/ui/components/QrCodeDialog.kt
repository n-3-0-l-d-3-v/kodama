package os.proximity.android.ui.components

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import os.proximity.android.verification.generateQrBitmap

/** Shows this device's own verification QR code, for someone else to scan. */
@Composable
fun MyQrCodeDialog(payload: String, fingerprint: String?, onDismiss: () -> Unit) {
    val bitmap = remember(payload) { generateQrBitmap(payload) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Your code") },
        text = {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Image(
                    bitmap = bitmap.asImageBitmap(),
                    contentDescription = "Your verification QR code",
                    modifier = Modifier.size(220.dp)
                )
                Spacer(Modifier.height(14.dp))
                fingerprint?.let {
                    FingerprintText(it)
                    Spacer(Modifier.height(10.dp))
                }
                Text(
                    "Have the other person scan this, or read the code above aloud.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center
                )
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Done") } }
    )
}
