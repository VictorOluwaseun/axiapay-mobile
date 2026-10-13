package com.kesofty.axiapay.ui.auth

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.*
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kesofty.axiapay.R
import com.kesofty.axiapay.domain.AuthContactType
import com.kesofty.axiapay.domain.AuthMethod
import com.kesofty.axiapay.ui.theme.*

/** Describes one UI choice without owning its selection or invoking an application operation. */
private data class AuthChoice<T>(
    val value: T,
    @param:StringRes @get:StringRes val label: Int,
    val testTag: String,
)

/**
 * Keeps contact choices compact and distinct from the primary action; selection stays in the VM.
 */
@Composable
internal fun AuthContactSelector(
    selected: AuthContactType,
    enabled: Boolean,
    onSelect: (AuthContactType) -> Unit,
) {
    val choices =
        listOf(
            AuthChoice(AuthContactType.Phone, R.string.auth_contact_phone, "auth-contact-phone"),
            AuthChoice(AuthContactType.Email, R.string.auth_contact_email, "auth-contact-email"),
        )
    SingleChoiceSegmentedButtonRow(
        Modifier.widthIn(max = 260.dp).fillMaxWidth().height(IntrinsicSize.Min),
    ) {
        choices.forEachIndexed { index, choice ->
            SegmentedButton(
                selected = selected == choice.value,
                onClick = { onSelect(choice.value) },
                shape = SegmentedButtonDefaults.itemShape(index, choices.size),
                modifier =
                    Modifier.weight(1f)
                        .fillMaxHeight()
                        .heightIn(min = 48.dp)
                        .testTag(choice.testTag),
                enabled = enabled,
                border = controlBorder(selected == choice.value, enabled),
                colors =
                    SegmentedButtonDefaults.colors(
                        activeContainerColor = Mint,
                        activeContentColor = Pine,
                        activeBorderColor = Pine,
                        inactiveContainerColor = Color.White,
                        inactiveContentColor = Ink,
                        inactiveBorderColor = ControlOutline,
                    ),
                label = {
                    Text(stringResource(choice.label), style = MaterialTheme.typography.labelLarge)
                },
            )
        }
    }
}

/** Shows quieter radio choices with a full-row touch target and a single accessible selection. */
@Composable
internal fun AuthMethodSelector(
    selected: AuthMethod,
    enabled: Boolean,
    onSelect: (AuthMethod) -> Unit,
) {
    val choices =
        listOf(
            AuthChoice(AuthMethod.OneTimeCode, R.string.auth_method_code, "auth-method-code"),
            AuthChoice(AuthMethod.Password, R.string.auth_method_password, "auth-method-password"),
        )
    Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min).selectableGroup()) {
        choices.forEach { choice ->
            Row(
                Modifier.weight(1f)
                    .fillMaxHeight()
                    .heightIn(min = 48.dp)
                    .testTag(choice.testTag)
                    .selectable(
                        selected = selected == choice.value,
                        enabled = enabled,
                        role = Role.RadioButton,
                        onClick = { onSelect(choice.value) },
                    )
                    .padding(vertical = 8.dp, horizontal = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                RadioButton(
                    selected = selected == choice.value,
                    onClick = null,
                    enabled = enabled,
                    colors =
                        RadioButtonDefaults.colors(
                            selectedColor = Pine,
                            unselectedColor = ControlOutline,
                        ),
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    stringResource(choice.label),
                    style = MaterialTheme.typography.bodyLarge,
                    color = if (enabled) Ink else Muted.copy(alpha = .38f),
                )
            }
        }
    }
}

/**
 * Renders value-owned fields with readable type, explicit IME actions and masked passwords.
 * Value-based input preserves the project's intent/state ownership; no credential is remembered or
 * saved by the composable. Visibility changes are also explicit ViewModel intents.
 */
@Composable
internal fun AuthTextField(
    value: String,
    @StringRes label: Int,
    @StringRes placeholder: Int,
    isProcessing: Boolean,
    isError: Boolean,
    keyboardType: KeyboardType,
    imeAction: ImeAction,
    onChange: (String) -> Unit,
    onDone: () -> Unit = {},
    isCode: Boolean = false,
    testTag: String,
    isPassword: Boolean = false,
    isPasswordVisible: Boolean = false,
    onTogglePasswordVisibility: () -> Unit = {},
) {
    val visibilityLabel = if (isPasswordVisible) R.string.auth_hide else R.string.auth_show
    val visibilityDescription =
        stringResource(
            if (isPasswordVisible) R.string.auth_hide_password else R.string.auth_show_password,
        )
    OutlinedTextField(
        value,
        onChange,
        modifier = Modifier.fillMaxWidth().testTag(testTag),
        label = { Text(stringResource(label), style = MaterialTheme.typography.bodyLarge) },
        placeholder = {
            Text(stringResource(placeholder), style = MaterialTheme.typography.bodyLarge)
        },
        textStyle =
            MaterialTheme.typography.bodyLarge.copy(
                fontSize = if (isCode) 26.sp else 18.sp,
                fontWeight = if (isCode) FontWeight.Bold else FontWeight.Medium,
                letterSpacing = if (isCode) 8.sp else 0.sp,
            ),
        visualTransformation =
            if (isPassword && !isPasswordVisible) PasswordVisualTransformation()
            else VisualTransformation.None,
        trailingIcon =
            if (isPassword) {
                {
                    TextButton(
                        onTogglePasswordVisibility,
                        enabled = !isProcessing,
                        modifier =
                            Modifier.heightIn(min = 48.dp).semantics {
                                contentDescription = visibilityDescription
                            },
                    ) {
                        Text(
                            stringResource(visibilityLabel),
                            style = MaterialTheme.typography.labelMedium,
                        )
                    }
                }
            } else null,
        shape = RoundedCornerShape(18.dp),
        singleLine = true,
        enabled = !isProcessing,
        isError = isError,
        keyboardOptions =
            KeyboardOptions(
                keyboardType = keyboardType,
                imeAction = imeAction,
                autoCorrectEnabled = false,
            ),
        keyboardActions = KeyboardActions(onDone = { onDone() }),
        colors =
            OutlinedTextFieldDefaults.colors(
                focusedContainerColor = Color.White,
                unfocusedContainerColor = Color.White,
                unfocusedBorderColor = ControlOutline,
                disabledBorderColor = ControlOutline.copy(alpha = .38f),
                focusedBorderColor = Pine,
            ),
    )
}
