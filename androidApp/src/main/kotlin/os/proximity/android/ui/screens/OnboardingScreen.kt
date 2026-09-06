package os.proximity.android.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import os.proximity.android.data.AppSettings
import os.proximity.android.ui.theme.LocalDecisionColors

/**
 * First run. Two jobs, in order of importance:
 *
 * 1. Say plainly what the app will never do. Trust is the product here, and
 *    a promise stated up front is checkable later against the audit log.
 * 2. Get a display name that isn't the user's real one by default.
 */
@Composable
fun OnboardingScreen(
    initialName: String,
    onFinish: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    var name by remember { mutableStateOf(initialName) }

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(28.dp)
    ) {
        Spacer(Modifier.height(32.dp))
        Text("Kodama", style = MaterialTheme.typography.displaySmall)
        Spacer(Modifier.height(10.dp))
        Text(
            "A temporary local network that forms between phones near you. " +
                "No internet, no account, no servers.",
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        Spacer(Modifier.height(32.dp))
        PromiseCard(
            title = "What this app will never do",
            promises = listOf(
                "Send anything without you approving it first",
                "Read your contacts, calendar, or location unless you turn it on",
                "Run code that another device sends you — ever",
                "Upload your messages or your activity log anywhere"
            ),
            positive = false
        )

        Spacer(Modifier.height(16.dp))
        PromiseCard(
            title = "What it does",
            promises = listOf(
                "Finds other Kodama phones nearby over Bluetooth",
                "Encrypts everything end to end, with keys kept in your phone's secure hardware",
                "Asks you before accepting a connection from someone new",
                "Writes down every decision it makes, so you can check it"
            ),
            positive = true
        )

        Spacer(Modifier.height(32.dp))
        Text("What should people see you as?", style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(4.dp))
        Text(
            "Nearby devices see this name once you connect. Consider not using your full real name.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(12.dp))
        OutlinedTextField(
            value = name,
            onValueChange = { if (it.length <= AppSettings.MAX_NAME_LENGTH) name = it },
            label = { Text("Display name") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )

        Spacer(Modifier.height(28.dp))
        Button(
            onClick = { onFinish(name) },
            modifier = Modifier.fillMaxWidth(),
            enabled = name.isNotBlank()
        ) {
            Text("Get started")
        }
        Spacer(Modifier.height(32.dp))
    }
}

@Composable
private fun PromiseCard(title: String, promises: List<String>, positive: Boolean) {
    val colors = LocalDecisionColors.current
    Surface(
        shape = MaterialTheme.shapes.large,
        color = if (positive) colors.allowContainer else MaterialTheme.colorScheme.surfaceVariant,
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(Modifier.padding(20.dp)) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                color = if (positive) colors.allow else MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(12.dp))
            promises.forEach { promise ->
                Row(
                    modifier = Modifier.padding(vertical = 5.dp),
                    verticalAlignment = Alignment.Top,
                    horizontalArrangement = Arrangement.Start
                ) {
                    Text(
                        text = if (positive) "•" else "✕",
                        style = MaterialTheme.typography.bodyMedium,
                        color = if (positive) colors.allow else colors.deny,
                        modifier = Modifier.padding(end = 12.dp)
                    )
                    Text(promise, style = MaterialTheme.typography.bodyMedium)
                }
            }
        }
    }
}
