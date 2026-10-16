package com.makefunstuff.herdr.ui.design

import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTouchHeightIsEqualTo
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.makefunstuff.herdr.ui.theme.HerdrTheme
import org.junit.Rule
import org.junit.Test

/** At 2x font scale the popups still show everything and keep 48dp touch targets. */
class FontScaleTest {
    @get:Rule val rule = createComposeRule()

    @Test fun dialogAtDoubleFontScale() {
        rule.setContent {
            CompositionLocalProvider(LocalDensity provides Density(LocalDensity.current.density, fontScale = 2f)) {
                HerdrTheme {
                    HerdrDialog(
                        onDismissRequest = {}, title = { Text("Set up the SSH key") },
                        text = { Text("The app signs in to your machines with an SSH key.") },
                        confirmButton = { TextButton(onClick = {}) { Text("Restore or import") } },
                        dismissButton = { TextButton(onClick = {}) { Text("Later") } },
                    )
                }
            }
        }
        rule.onNodeWithText("Set up the SSH key").assertIsDisplayed()
        rule.onNodeWithText("Restore or import").assertIsDisplayed()
        rule.onNodeWithText("Later").assertIsDisplayed().assertTouchHeightIsEqualTo(48.dp)
    }

    @Test fun menuAtDoubleFontScale() {
        rule.setContent {
            CompositionLocalProvider(LocalDensity provides Density(LocalDensity.current.density, fontScale = 2f)) {
                HerdrTheme { HerdrMenu(true, {}) { HerdrMenuItem({ Text("Import herdr machines from here") }, {}) } }
            }
        }
        rule.onNodeWithText("Import herdr machines from here").assertIsDisplayed()
    }
}
