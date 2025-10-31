package pl.zarajczyk.familyrulesandroid.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color


object FamilyRulesColors {
	val NORMAL_BACKGROUND: Color
		@Composable get() = if (isSystemInDarkTheme()) Color(0xFF3B3B3B) else Color(0xFFEEEEEE)

	val BLOCKING_COLOR: Color
		@Composable get() = if (isSystemInDarkTheme()) Color(0xFF7A2A2A) else Color(0xFFFFDEDE)

	val SECONDARY_BACKGROUND_COLOR: Color
		@Composable get() = if (isSystemInDarkTheme()) Color(0xFF757575) else Color(0xFFF5F5F5)

	val PERMISSION_GRANTED_COLOR: Color
		@Composable get() = if (isSystemInDarkTheme()) Color(0xFF2E7D32) else Color(0xFFC8E6C9)

	val PERMISSION_NOT_GRANTED_COLOR: Color
		@Composable get() = if (isSystemInDarkTheme()) Color(0xFF7A2A2A) else Color(0xFFFFDEDE)

	val TEXT_COLOR: Color
		@Composable get() = if (isSystemInDarkTheme()) Color(0xFFEDEDED) else Color(0xFF212121)

	val ERROR_TEXT_COLOR: Color
		@Composable get() = if (isSystemInDarkTheme()) Color(0xFFFF6B6B) else Color(0xFFFF0000)

	val BUTTON_COLOR: Color
		@Composable get() = if (isSystemInDarkTheme()) Color(0xFF2B5C7A) else Color(0xFF8DC1EA)

	val DISABLED_BUTTON_COLOR: Color
		@Composable get() = if (isSystemInDarkTheme()) Color(0xFF555555) else Color(0xFFBEBEBE)
}