package com.project.musmesh

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import org.junit.Rule
import org.junit.Test

class UiTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    @Test fun roleSelectionIsInSpanish() {
        compose.onNodeWithText("MusMesh").assertIsDisplayed()
        compose.onNodeWithText("Crear sala").assertIsDisplayed()
        compose.onNodeWithText("Unirse a sala").assertIsDisplayed()
    }
}
