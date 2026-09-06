package os.proximity.android.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
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
import os.proximity.android.ui.components.SectionHeader
import os.proximity.android.ui.theme.LocalDecisionColors
import os.proximity.shared.capability.CapabilityCatalog
import os.proximity.shared.capability.CapabilityDefinition
import os.proximity.shared.guardrail.PolicyCatalog
import os.proximity.shared.guardrail.PolicyOption

@Composable
fun PoliciesScreen(
    enabledIds: Set<String>,
    enabledCapabilities: Set<String>,
    runInBackground: Boolean,
    displayName: String,
    onToggle: (String, Boolean) -> Unit,
    onToggleCapability: (String, Boolean) -> Unit,
    onRunInBackgroundChange: (Boolean) -> Unit,
    onDisplayNameChange: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    LazyColumn(modifier = modifier.fillMaxSize()) {
        item {
            Column(Modifier.padding(horizontal = 20.dp, vertical = 12.dp)) {
                Text("Your rules", style = MaterialTheme.typography.headlineSmall)
                Spacer(Modifier.height(4.dp))
                Text(
                    "Anything not allowed by a rule is blocked. That's the default, " +
                        "and it can't be turned off.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        item { AlwaysOnCard() }

        item { SectionHeader("Rules you control") }
        items(PolicyCatalog.options, key = { it.id }) { option ->
            PolicyRow(
                option = option,
                enabled = option.id in enabledIds,
                onToggle = { onToggle(option.id, it) }
            )
        }

        item { SectionHeader("What you offer nearby devices") }
        item {
            Text(
                "These say what you are willing to be asked for. They are not permissions — " +
                    "every actual request still goes through your rules above.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp)
            )
        }
        items(CapabilityCatalog.definitions, key = { "cap-${it.name}" }) { definition ->
            CapabilityRow(
                definition = definition,
                enabled = definition.name in enabledCapabilities,
                onToggle = { onToggleCapability(definition.name, it) }
            )
        }

        item { SectionHeader("Running in the background") }
        item {
            SimpleToggleRow(
                title = "Keep the mesh running when I leave the app",
                explanation = "Off by default. Turned on, Kodama keeps looking for " +
                    "nearby devices with a notification showing, and uses more battery. " +
                    "Turned off, it only runs while you have the app open.",
                enabled = runInBackground,
                onToggle = onRunInBackgroundChange
            )
        }

        item { SectionHeader("How you appear") }
        item { DisplayNameField(displayName, onDisplayNameChange) }
        item { Spacer(Modifier.height(32.dp)) }
    }
}

/**
 * Surfacing the safety floor matters: a user should be able to see which
 * protections are not theirs to disable, rather than discovering the limit
 * only when something is refused.
 */
@Composable
private fun AlwaysOnCard() {
    val colors = LocalDecisionColors.current
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(16.dp),
        shape = MaterialTheme.shapes.large,
        color = colors.denyContainer
    ) {
        Column(Modifier.padding(20.dp)) {
            Text(
                "Always on — cannot be turned off",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                color = colors.deny
            )
            Spacer(Modifier.height(8.dp))
            Text(
                "Code sent by another device is never run, whatever your other settings say. " +
                    "This is built into the app and there is deliberately no switch for it.",
                style = MaterialTheme.typography.bodySmall,
                color = colors.deny
            )
        }
    }
}

@Composable
private fun PolicyRow(option: PolicyOption, enabled: Boolean, onToggle: (Boolean) -> Unit) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp),
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surfaceVariant
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.Top,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    option.title,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Medium
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    option.explanation,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Spacer(Modifier.padding(horizontal = 6.dp))
            Switch(checked = enabled, onCheckedChange = onToggle)
        }
    }
}

@Composable
private fun CapabilityRow(
    definition: CapabilityDefinition,
    enabled: Boolean,
    onToggle: (Boolean) -> Unit
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp),
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surfaceVariant
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.Top,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    definition.title,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Medium
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    definition.explanation,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Spacer(Modifier.padding(horizontal = 6.dp))
            Switch(checked = enabled, onCheckedChange = onToggle)
        }
    }
}

@Composable
private fun SimpleToggleRow(
    title: String,
    explanation: String,
    enabled: Boolean,
    onToggle: (Boolean) -> Unit
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp),
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surfaceVariant
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.Top,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    title,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Medium
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    explanation,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Spacer(Modifier.padding(horizontal = 6.dp))
            Switch(checked = enabled, onCheckedChange = onToggle)
        }
    }
}

@Composable
private fun DisplayNameField(displayName: String, onChange: (String) -> Unit) {
    var draft by remember(displayName) { mutableStateOf(displayName) }

    Column(Modifier.padding(horizontal = 16.dp)) {
        OutlinedTextField(
            value = draft,
            onValueChange = {
                if (it.length <= AppSettings.MAX_NAME_LENGTH) {
                    draft = it
                    onChange(it)
                }
            },
            label = { Text("Display name") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )
        Spacer(Modifier.height(6.dp))
        Text(
            "Shown to devices you connect to.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}
