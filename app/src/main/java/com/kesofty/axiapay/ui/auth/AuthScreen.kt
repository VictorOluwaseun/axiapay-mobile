package com.kesofty.axiapay.ui.auth

import androidx.activity.compose.BackHandler
import androidx.annotation.StringRes
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.kesofty.axiapay.R
import com.kesofty.axiapay.domain.AuthContactType
import com.kesofty.axiapay.domain.AuthMethod
import com.kesofty.axiapay.presentation.auth.*
import com.kesofty.axiapay.ui.components.*
import com.kesofty.axiapay.ui.theme.*

/** Renders the account-entry prototype; all inputs and actions are owned by AuthViewModel. */
@Composable
fun AuthScreen(state: AuthUiState, offline: Boolean, onIntent: (AuthIntent) -> Unit) {
    BackHandler(state.screen != AuthScreen.Welcome) { onIntent(AuthIntent.Back) }
    Surface(color = Canvas, modifier = Modifier.fillMaxSize()) {
        Box(
            Modifier.fillMaxSize().safeDrawingPadding().imePadding(),
            contentAlignment = Alignment.TopCenter,
        ) {
            key(state.screen) {
                Column(
                    Modifier.widthIn(max = 480.dp)
                        .fillMaxSize()
                        .verticalScroll(rememberScrollState())
                        .padding(horizontal = 26.dp)
                        .padding(top = 18.dp, bottom = 24.dp)
                        .testTag("auth-screen"),
                ) {
                    AuthTopBar(state.screen == AuthScreen.Welcome) { onIntent(AuthIntent.Back) }
                    Spacer(Modifier.height(24.dp))
                    if (offline) {
                        AuthNotice(R.string.auth_offline, Glyph.Offline)
                        Spacer(Modifier.height(20.dp))
                    }
                    when (state.screen) {
                        AuthScreen.Welcome ->
                            AuthWelcome(
                                { onIntent(AuthIntent.CreateAccount) },
                                { onIntent(AuthIntent.SignIn) },
                                { onIntent(AuthIntent.ExploreDemo) },
                            )
                        AuthScreen.SignIn,
                        AuthScreen.CreateAccount -> AccountForm(state, onIntent)
                        AuthScreen.Verification -> VerificationForm(state, onIntent)
                        AuthScreen.Complete ->
                            CompletionContent { onIntent(AuthIntent.ExploreDemo) }
                        AuthScreen.Dashboard -> Unit
                    }
                }
            }
        }
    }
}

/** Keeps branding and a clear preview label visible without adding another navigation layer. */
@Composable
private fun AuthTopBar(isWelcome: Boolean, onBack: () -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        if (!isWelcome) {
            IconButton(onBack, Modifier.size(48.dp)) {
                AxiaIcon(
                    Glyph.ArrowRight,
                    Modifier.rotate(180f),
                    description = stringResource(R.string.auth_back),
                )
            }
            Spacer(Modifier.width(6.dp))
        }
        AxiaIcon(Glyph.Brand, Modifier.size(28.dp), Pine)
        Text(
            stringResource(R.string.auth_brand),
            Modifier.weight(1f).padding(start = 8.dp),
            style = MaterialTheme.typography.titleLarge,
        )
        Text(
            stringResource(R.string.auth_demo_label),
            Modifier.background(Mint.copy(alpha = .55f), CircleShape)
                .padding(horizontal = 14.dp, vertical = 7.dp),
            color = Pine,
            style = MaterialTheme.typography.labelMedium,
        )
    }
}

/** Gives each form an accessible heading with large Manrope type and a readable supporting line. */
@Composable
internal fun AuthHeading(@StringRes title: Int, @StringRes body: Int) {
    Text(
        stringResource(title),
        style = MaterialTheme.typography.displaySmall,
        color = Ink,
        modifier = Modifier.semantics { heading() },
    )
    Spacer(Modifier.height(12.dp))
    Text(stringResource(body), style = MaterialTheme.typography.bodyLarge, color = Muted)
}

/** Displays the selected signup/sign-in contact and method with one explicit submission intent. */
@Composable
private fun AccountForm(state: AuthUiState, onIntent: (AuthIntent) -> Unit) {
    val focus = LocalFocusManager.current
    val submit = {
        focus.clearFocus()
        onIntent(AuthIntent.Continue)
    }
    val isPhone = state.contactType == AuthContactType.Phone
    val usesPassword = state.method == AuthMethod.Password
    AccountHeading(
        if (state.isCreatingAccount) R.string.auth_create_title else R.string.auth_sign_in_title,
        if (state.isCreatingAccount) R.string.auth_create_body else R.string.auth_sign_in_body,
    )
    Spacer(Modifier.height(28.dp))
    if (state.isCreatingAccount) {
        AuthTextField(
            state.name,
            R.string.auth_name,
            R.string.auth_name_hint,
            state.isProcessing,
            state.error == AuthError.Name,
            KeyboardType.Text,
            ImeAction.Next,
            { onIntent(AuthIntent.EditName(it)) },
            testTag = "auth-name",
        )
        Spacer(Modifier.height(20.dp))
    }
    AuthContactSelector(state.contactType, !state.isProcessing) {
        focus.clearFocus()
        onIntent(AuthIntent.SelectContactType(it))
    }
    Spacer(Modifier.height(8.dp))
    AuthTextField(
        state.contact,
        if (isPhone) R.string.auth_phone else R.string.auth_email,
        if (isPhone) R.string.auth_phone_hint else R.string.auth_email_hint,
        state.isProcessing,
        state.error == AuthError.Phone || state.error == AuthError.Email,
        if (isPhone) KeyboardType.Phone else KeyboardType.Email,
        if (usesPassword) ImeAction.Next else ImeAction.Done,
        { onIntent(AuthIntent.EditContact(it)) },
        submit,
        testTag = "auth-contact",
    )
    if (isPhone) {
        Spacer(Modifier.height(8.dp))
        Text(
            stringResource(R.string.auth_country_hint),
            color = Muted,
            style = MaterialTheme.typography.bodyMedium,
        )
    }
    Spacer(Modifier.height(22.dp))
    Text(
        stringResource(
            if (state.isCreatingAccount) R.string.auth_signup_method
            else R.string.auth_signin_method,
        ),
        color = Muted,
        style = MaterialTheme.typography.bodyMedium,
    )
    Spacer(Modifier.height(4.dp))
    AuthMethodSelector(state.method, !state.isProcessing) {
        focus.clearFocus()
        onIntent(AuthIntent.SelectMethod(it))
    }
    if (usesPassword) {
        Spacer(Modifier.height(8.dp))
        AuthTextField(
            state.password,
            R.string.auth_password,
            R.string.auth_password_hint,
            state.isProcessing,
            state.error == AuthError.Password,
            KeyboardType.Password,
            ImeAction.Done,
            { onIntent(AuthIntent.EditPassword(it)) },
            submit,
            testTag = "auth-password",
            isPassword = true,
            isPasswordVisible = state.isPasswordVisible,
            onTogglePasswordVisibility = { onIntent(AuthIntent.TogglePasswordVisibility) },
        )
        Spacer(Modifier.height(10.dp))
        Text(
            stringResource(R.string.auth_password_demo_hint),
            color = Muted,
            style = MaterialTheme.typography.bodyMedium,
        )
    }
    AuthErrorMessage(state.error)
    Spacer(Modifier.height(24.dp))
    AuthSubmitButton(
        if (usesPassword && !state.isCreatingAccount) R.string.auth_sign_in
        else R.string.auth_continue,
        state.isProcessing,
        submit,
    )
    Spacer(Modifier.height(18.dp))
    Text(
        stringResource(R.string.auth_preview_notice),
        style = MaterialTheme.typography.bodyMedium,
        color = Muted,
    )
    Spacer(Modifier.height(18.dp))
    AccountFooter(state.isCreatingAccount, onIntent)
}

/**
 * Keeps form headings concise while retaining the established typeface and accessible hierarchy.
 */
@Composable
private fun AccountHeading(@StringRes title: Int, @StringRes body: Int) {
    Text(
        stringResource(title),
        style = MaterialTheme.typography.headlineLarge,
        color = Ink,
        modifier = Modifier.semantics { heading() },
    )
    Spacer(Modifier.height(10.dp))
    Text(stringResource(body), style = MaterialTheme.typography.bodyLarge, color = Muted)
}

/** Groups the alternate account route into one action, keeping demo entry available below it. */
@Composable
private fun AccountFooter(isCreatingAccount: Boolean, onIntent: (AuthIntent) -> Unit) {
    TextButton(
        { onIntent(if (isCreatingAccount) AuthIntent.SignIn else AuthIntent.CreateAccount) },
        modifier = Modifier.fillMaxWidth(),
        contentPadding = PaddingValues(vertical = 12.dp),
    ) {
        Text(
            stringResource(
                if (isCreatingAccount) R.string.auth_already_member else R.string.auth_new_member,
            ),
            modifier = Modifier.weight(1f, fill = false),
            style = MaterialTheme.typography.bodyLarge,
            color = Muted,
        )
        Spacer(Modifier.width(6.dp))
        Text(
            stringResource(
                if (isCreatingAccount) R.string.auth_sign_in else R.string.auth_create_account,
            ),
            style = MaterialTheme.typography.labelLarge,
        )
    }
    TextButton(
        { onIntent(AuthIntent.ExploreDemo) },
        modifier = Modifier.fillMaxWidth(),
        contentPadding = PaddingValues(vertical = 12.dp),
    ) {
        Text(
            stringResource(R.string.auth_explore),
            style = MaterialTheme.typography.bodyMedium,
            color = Muted,
        )
    }
}

/**
 * Uses one code input for paste, accessibility and keyboard support; code entry never auto-submits.
 */
@Composable
private fun VerificationForm(state: AuthUiState, onIntent: (AuthIntent) -> Unit) {
    val focus = LocalFocusManager.current
    val submit = {
        focus.clearFocus()
        onIntent(AuthIntent.Continue)
    }
    IconTile(Glyph.Shield, Modifier.padding(bottom = 24.dp), Lavender)
    AuthHeading(
        R.string.auth_verify_title,
        if (state.isCreatingAccount) R.string.auth_verify_signup_body
        else R.string.auth_verify_body,
    )
    Spacer(Modifier.height(22.dp))
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(
                stringResource(
                    if (state.contactType == AuthContactType.Phone) R.string.auth_phone
                    else R.string.auth_email,
                ),
                style = MaterialTheme.typography.bodyMedium,
                color = Muted,
            )
            Text(state.contact, style = MaterialTheme.typography.titleLarge)
        }
        TextButton({ onIntent(AuthIntent.Back) }) {
            Text(stringResource(R.string.auth_change), style = MaterialTheme.typography.labelMedium)
        }
    }
    Spacer(Modifier.height(28.dp))
    AuthTextField(
        state.code,
        R.string.auth_code,
        R.string.auth_code_hint,
        state.isProcessing,
        state.error == AuthError.Code,
        KeyboardType.NumberPassword,
        ImeAction.Done,
        { onIntent(AuthIntent.EditCode(it)) },
        submit,
        isCode = true,
        testTag = "auth-code",
    )
    AuthErrorMessage(state.error)
    Spacer(Modifier.height(24.dp))
    AuthSubmitButton(R.string.auth_verify, state.isProcessing, submit)
    Spacer(Modifier.height(18.dp))
    AuthNotice(R.string.auth_code_demo_notice, Glyph.Help)
    TextButton(
        {
            focus.clearFocus()
            onIntent(AuthIntent.ResetCodePreview)
        },
        enabled = !state.isProcessing,
        contentPadding = PaddingValues(vertical = 16.dp),
    ) {
        Text(stringResource(R.string.auth_reset_code), style = MaterialTheme.typography.labelLarge)
    }
    if (state.previewReset) {
        Text(
            stringResource(R.string.auth_code_reset),
            style = MaterialTheme.typography.bodyMedium,
            color = Pine,
            modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
        )
    }
}

/**
 * Confirms only demo readiness, avoiding any claim that an account or identity has been verified.
 */
@Composable
private fun CompletionContent(onExplore: () -> Unit) {
    Spacer(Modifier.height(32.dp))
    Box(
        Modifier.size(104.dp).background(Mint, RoundedCornerShape(36.dp)),
        contentAlignment = Alignment.Center,
    ) {
        AxiaIcon(Glyph.Check, Modifier.size(48.dp), Pine)
    }
    Spacer(Modifier.height(32.dp))
    AuthHeading(R.string.auth_complete_title, R.string.auth_complete_body)
    Spacer(Modifier.height(28.dp))
    Column(
        Modifier.fillMaxWidth().background(Color.White, RoundedCornerShape(24.dp)).padding(22.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp),
    ) {
        PreviewFeature(Glyph.Wallet, R.string.auth_feature_wallet)
        PreviewFeature(Glyph.Grid, R.string.auth_feature_payments)
        PreviewFeature(Glyph.Calendar, R.string.auth_feature_plans)
    }
    Spacer(Modifier.height(32.dp))
    PrimaryButton(stringResource(R.string.auth_open_demo), onClick = onExplore)
}

/** Explains one available demo capability with the same icon treatment as the dashboard. */
@Composable
private fun PreviewFeature(icon: Glyph, @StringRes label: Int) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        IconTile(icon)
        Text(
            stringResource(label),
            Modifier.padding(start = 14.dp),
            style = MaterialTheme.typography.bodyLarge,
        )
    }
}

/** Places validation in the form and announces changes to accessibility services. */
@Composable
private fun AuthErrorMessage(error: AuthError?) {
    if (error == null) return
    val message =
        when (error) {
            AuthError.Phone -> R.string.auth_phone_error
            AuthError.Email -> R.string.auth_email_error
            AuthError.Password -> R.string.auth_password_error
            AuthError.Name -> R.string.auth_name_error
            AuthError.Code -> R.string.auth_code_error
            AuthError.Unavailable -> R.string.auth_unavailable_error
        }
    Text(
        stringResource(message),
        color = Danger,
        style = MaterialTheme.typography.bodyMedium,
        modifier = Modifier.padding(top = 12.dp).semantics { liveRegion = LiveRegionMode.Polite },
    )
}

/**
 * Shows explicit progress in the button while keeping its layout stable and preventing repeat taps.
 */
@Composable
private fun AuthSubmitButton(@StringRes label: Int, isProcessing: Boolean, onClick: () -> Unit) {
    Button(
        onClick,
        Modifier.fillMaxWidth().heightIn(min = 58.dp).testTag("auth-submit"),
        enabled = !isProcessing,
        shape = RoundedCornerShape(18.dp),
        contentPadding = PaddingValues(16.dp),
    ) {
        if (isProcessing) {
            CircularProgressIndicator(Modifier.size(20.dp), color = Muted, strokeWidth = 2.dp)
            Spacer(Modifier.width(12.dp))
        }
        Text(
            stringResource(if (isProcessing) R.string.auth_working else label),
            style = MaterialTheme.typography.labelLarge,
        )
    }
}

/** Explains demo and connectivity conditions using a readable neutral inline panel. */
@Composable
private fun AuthNotice(@StringRes message: Int, icon: Glyph) {
    Row(
        Modifier.fillMaxWidth()
            .background(Line.copy(alpha = .5f), RoundedCornerShape(18.dp))
            .padding(16.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        AxiaIcon(icon, Modifier.padding(top = 2.dp).size(20.dp), Muted)
        Text(stringResource(message), style = MaterialTheme.typography.bodyMedium, color = Muted)
    }
}
