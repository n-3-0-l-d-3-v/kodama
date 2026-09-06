package os.proximity.android.ui.theme

import androidx.compose.ui.graphics.Color

/**
 * Kodama palette.
 *
 * The palette is built around a "signal" metaphor: cool blues for the mesh
 * and presence, warm amber for anything awaiting a human decision, and a
 * decisive red for blocked actions. Guardrail decisions must be readable at
 * a glance, so Allow / Ask / Deny each own a distinct hue that survives both
 * light and dark themes.
 */

// Brand — signal blue
val Signal10 = Color(0xFF001B3D)
val Signal20 = Color(0xFF003062)
val Signal30 = Color(0xFF00468A)
val Signal40 = Color(0xFF005FB3)
val Signal80 = Color(0xFFA9C7FF)
val Signal90 = Color(0xFFD5E3FF)

// Secondary — mesh teal
val Mesh10 = Color(0xFF00201C)
val Mesh20 = Color(0xFF003731)
val Mesh30 = Color(0xFF005047)
val Mesh40 = Color(0xFF006B5F)
val Mesh80 = Color(0xFF62DBC7)
val Mesh90 = Color(0xFF7FF8E3)

// Neutrals
val Neutral6 = Color(0xFF0E1116)
val Neutral10 = Color(0xFF13171D)
val Neutral12 = Color(0xFF191D24)
val Neutral17 = Color(0xFF21262E)
val Neutral20 = Color(0xFF283039)
val Neutral90 = Color(0xFFE2E2E6)
val Neutral95 = Color(0xFFF0F0F4)
val Neutral99 = Color(0xFFFDFBFF)

// Guardrail decision semantics
val AllowGreen = Color(0xFF1F8A54)
val AllowGreenContainer = Color(0xFFB7F2CE)
val AllowGreenDark = Color(0xFF6FDDA0)

val AskAmber = Color(0xFF8A6100)
val AskAmberContainer = Color(0xFFFFDEA6)
val AskAmberDark = Color(0xFFFFBD3D)

val DenyRed = Color(0xFFB3261E)
val DenyRedContainer = Color(0xFFF9DEDC)
val DenyRedDark = Color(0xFFFFB4AB)
