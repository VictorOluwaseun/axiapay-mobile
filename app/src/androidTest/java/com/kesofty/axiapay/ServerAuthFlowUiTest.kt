package com.kesofty.axiapay

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.ViewModelProvider
import androidx.test.platform.app.InstrumentationRegistry
import com.kesofty.axiapay.data.auth.*
import com.kesofty.axiapay.domain.*
import com.kesofty.axiapay.domain.auth.*
import com.kesofty.axiapay.domain.wallet.WalletLookupResult
import com.kesofty.axiapay.presentation.auth.AuthScreen
import com.kesofty.axiapay.presentation.auth.AuthViewModel
import com.kesofty.axiapay.ui.walletMoney
import java.time.Clock
import java.util.Locale
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlin.random.Random
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import org.junit.*
import org.junit.Assert.*

/**
 * Opt-in local integration through the real Hilt graph, API, PostgreSQL and Mailpit. Requires the
 * selected dev emulator and runLocalAuth=true; codes and synthetic credentials are never logged.
 */
class ServerAuthFlowUiTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    private val client =
        OkHttpClient.Builder()
            .retryOnConnectionFailure(false)
            .followRedirects(false)
            .callTimeout(20, TimeUnit.SECONDS)
            .build()
    // Mailpit reads are safe GETs and may recover an idle socket closed during the real cooldown.
    private val captureClient = client.newBuilder().retryOnConnectionFailure(true).build()
    private var ownsTestSession = false
    private val context
        get() = InstrumentationRegistry.getInstrumentation().targetContext

    private val sessions
        get() = KeystoreAuthenticationSessionStore(context, Dispatchers.IO)

    private val api
        get() =
            HttpAuthenticationRepository(
                BuildConfig.API_BASE_URL,
                true,
                client,
                Dispatchers.IO,
                Clock.systemUTC(),
            )

    /** Requires an explicit local run and refuses to overwrite a preexisting signed-in session. */
    @Before
    fun requireLocalApi() = runBlocking {
        Assume.assumeTrue(
            BuildConfig.FLAVOR == "dev" &&
                InstrumentationRegistry.getArguments().getString("runLocalAuth") == "true",
        )
        Assume.assumeTrue(
            BuildConfig.API_BASE_URL.toHttpUrl().host in
                setOf("10.0.2.2", "127.0.0.1", "localhost"),
        )
        Assume.assumeTrue(
            "Use a dev emulator without an existing auth session",
            sessions.read() == null,
        )
        ownsTestSession = true
        waitForScreen(AuthScreen.Welcome)
    }

    /** Revokes only this test's own active session before releasing its HTTP resources. */
    @After
    fun cleanup() = runBlocking {
        if (ownsTestSession) {
            sessions.read()?.let {
                api.logout(it.accessToken)
                sessions.clear(it)
            }
        }
        client.dispatcher.cancelAll()
        client.connectionPool.evictAll()
        client.dispatcher.executorService.shutdown()
    }

    /**
     * All eight contact/method paths issue real sessions, restore identity and revoke on sign-out.
     */
    @Test
    fun allContactAndMethodFlowsAuthenticateThroughTheLocalApi() {
        val accounts = mutableListOf<LocalTestAccount>()
        for (kind in AuthContactType.entries) {
            for (method in AuthMethod.entries) {
                val contact =
                    if (kind == AuthContactType.Phone) "080" + Random.nextInt(10000000, 99999999)
                    else "mobile-" + UUID.randomUUID().toString().take(12) + "+auth@example.test"
                val account =
                    LocalTestAccount(kind, method, contact, " mobile integration passphrase ")
                compose.onNodeWithText("Get started").performScrollTo().performClick()
                enterContact(account)
                compose.onNodeWithTag("auth-name").assertDoesNotExist()
                chooseMethodAndPassword(account)
                compose.onNodeWithTag("auth-submit").performScrollTo().performClick()
                waitForScreen(AuthScreen.Verification)
                val challenge = requireNotNull(viewModel().uiState.value.challenge)
                account.resendAt = challenge.resendAt.toEpochMilli()
                compose.onNodeWithTag("auth-password").assertDoesNotExist()
                val code = readLocalCode(account, challenge.id)
                compose.onNodeWithTag("auth-code").performScrollTo().performTextInput(code)
                compose.onNodeWithTag("auth-submit").performScrollTo().performClick()
                waitForScreen(AuthScreen.Dashboard)
                assertAccount(account)
                if (accounts.isEmpty()) {
                    assertNewOwnerRestoresSession(account)
                }
                compose.activityRule.scenario.recreate()
                waitForScreen(AuthScreen.Dashboard)
                assertAccount(account)
                signOutAndVerifyRevocation()
                accounts += account
            }
        }
        for (account in accounts.filter { it.method == AuthMethod.Password }) {
            openSignIn(account)
            chooseMethodAndPassword(account)
            compose.onNodeWithTag("auth-submit").performScrollTo().performClick()
            waitForScreen(AuthScreen.Dashboard)
            assertAccount(account)
            signOutAndVerifyRevocation()
        }
        // Preserve the real API's contact cooldown instead of bypassing limits in tests.
        val deadline = accounts.maxOf { it.resendAt } + 1000
        while (System.currentTimeMillis() < deadline) {
            Thread.sleep(250)
        }
        for (account in accounts) {
            openSignIn(account)
            compose.onNodeWithTag("auth-submit").performScrollTo().performClick()
            waitForScreen(AuthScreen.Verification)
            val challenge = requireNotNull(viewModel().uiState.value.challenge)
            compose
                .onNodeWithTag("auth-code")
                .performScrollTo()
                .performTextInput(readLocalCode(account, challenge.id))
            compose.onNodeWithTag("auth-submit").performScrollTo().performClick()
            waitForScreen(AuthScreen.Dashboard)
            assertAccount(account)
            signOutAndVerifyRevocation()
        }
    }

    /** Opens only the public sign-in route and leaves the default code method explicit. */
    private fun openSignIn(account: LocalTestAccount) {
        compose.onNodeWithText("I already have an account").performScrollTo().performClick()
        enterContact(account)
    }

    /** Enters the selected contact through its real form, including local phone normalization. */
    private fun enterContact(account: LocalTestAccount) {
        if (account.kind == AuthContactType.Email) {
            compose.onNodeWithTag("auth-contact-email").performClick()
        }
        compose.onNodeWithTag("auth-contact").performScrollTo().performTextInput(account.contact)
    }

    /**
     * Selects the public method control, preserving exact password whitespace in password flows.
     */
    private fun chooseMethodAndPassword(account: LocalTestAccount) {
        if (account.method != AuthMethod.Password) {
            return
        }
        compose.onNodeWithTag("auth-method-password").performScrollTo().performClick()
        compose.onNodeWithTag("auth-password").performScrollTo().performTextInput(account.password)
    }

    /**
     * A new lifecycle owner rechecks the real persisted session through its generated Hilt graph.
     */
    private fun assertNewOwnerRestoresSession(account: LocalTestAccount) {
        lateinit var restored: AuthViewModel
        compose.runOnUiThread {
            restored =
                ViewModelProvider(compose.activity)["cold-auth-check", AuthViewModel::class.java]
        }
        compose.waitUntil(20000) {
            restored.uiState.value.account != null && !restored.uiState.value.isProcessing
        }
        assertEquals(account.canonicalContact, restored.uiState.value.account?.contact?.value)
        assertEquals(AuthScreen.Dashboard, restored.uiState.value.screen)
        assertEquals("", restored.uiState.value.password)
        compose.onNodeWithText("You’re signed in.").assertDoesNotExist()
        compose.activityRule.scenario.recreate()
        waitForScreen(AuthScreen.Dashboard)
        compose.onNodeWithTag("home-wallet-balance").assertExists()
    }

    /** Home opens directly with the verified wallet; account details remain an explicit action. */
    private fun assertAccount(account: LocalTestAccount) {
        compose.onNodeWithTag("home-dashboard").assertIsDisplayed()
        compose.onNodeWithText("You’re signed in.").assertDoesNotExist()
        compose.onNodeWithText("Open dashboard").assertDoesNotExist()
        assertEquals(account.canonicalContact, viewModel().uiState.value.account?.contact?.value)
        assertFalse(viewModel().uiState.value.isDemo)
        assertEquals("", viewModel().uiState.value.password)
        assertEquals("", viewModel().uiState.value.code)
        val loaded = viewModel().uiState.value.wallet as WalletLookupResult.Loaded
        assertEquals("NGN", loaded.wallet.currency)
        assertEquals(java.math.BigInteger.ZERO, loaded.wallet.availableMinorUnits)
        compose
            .onNodeWithTag("home-wallet-balance")
            .assertTextEquals(
                walletMoney(
                    loaded.wallet.availableMinorUnits,
                    loaded.wallet.currency,
                    Locale.forLanguageTag("en-NG"),
                ),
            )
        compose.onNodeWithText("₦128,450.00").assertDoesNotExist()
        openAccountDetails()
        compose.onNodeWithTag("auth-account-contact").assertTextEquals(account.canonicalContact)
        compose
            .onNodeWithTag("wallet-id")
            .performScrollTo()
            .assertTextContains(loaded.wallet.id, substring = true)
        compose
            .onNodeWithContentDescription(context.getString(R.string.auth_back))
            .performScrollTo()
            .performClick()
        waitForScreen(AuthScreen.Dashboard)
    }

    /** Use the persistent Profile entry instead of replacing the dashboard's wallet actions. */
    private fun openAccountDetails() {
        compose.onNodeWithContentDescription("Open profile").performClick()
        compose
            .onNodeWithText(context.getString(R.string.auth_account_shortcut))
            .performScrollTo()
            .performClick()
        waitForScreen(AuthScreen.Complete)
    }

    /** Verifies that the server actually revoked the opaque session and local storage is empty. */
    private fun signOutAndVerifyRevocation() {
        openAccountDetails()
        val token = runBlocking { requireNotNull(sessions.read()).accessToken }
        compose.onNodeWithText("Sign out").performScrollTo().performClick()
        waitForScreen(AuthScreen.Welcome)
        runBlocking {
            assertNull(sessions.read())
            try {
                api.currentAccount(token)
                fail("Revoked sessions must not authenticate")
            } catch (failure: AuthenticationFailure) {
                assertEquals(AccountAccessIssue.InvalidCredentials, failure.issue)
            }
        }
    }

    /** Reads only this synthetic contact's matching challenge from the local capture service. */
    private fun readLocalCode(account: LocalTestAccount, challengeId: String): String {
        val deliveryContact =
            if (account.kind == AuthContactType.Phone)
                "sms+" + account.canonicalContact.drop(1) + "@sms.axiapay.test"
            else account.contact
        val base = "http://10.0.2.2:58025/".toHttpUrl()
        val until = System.currentTimeMillis() + 10000
        while (System.currentTimeMillis() < until) {
            val search =
                base
                    .newBuilder()
                    .addPathSegments("api/v1/search")
                    .addQueryParameter("query", "to:" + deliveryContact)
                    .build()
            val matches = readJson(search.toString()).getJSONArray("messages")
            for (index in 0 until matches.length()) {
                val messageId = matches.getJSONObject(index).getString("ID")
                val message =
                    readJson(
                        base
                            .newBuilder()
                            .addPathSegments("api/v1/message")
                            .addPathSegment(messageId)
                            .build()
                            .toString(),
                    )
                val text = message.getString("Text")
                if (text.contains(challengeId)) {
                    return requireNotNull(Regex("code is ([0-9]{6})").find(text)).groupValues[1]
                }
            }
            Thread.sleep(200)
        }
        error("Local code delivery was not captured for this test challenge")
    }

    /** Keeps local delivery payloads private and closes every HTTP response. */
    private fun readJson(url: String): JSONObject =
        captureClient.newCall(Request.Builder().url(url).build()).execute().use { response ->
            check(response.isSuccessful) { "Local capture endpoint unavailable" }
            JSONObject(response.body.string())
        }

    /** Reads lifecycle-owned state only to coordinate actual asynchronous UI/HTTP completion. */
    private fun viewModel(): AuthViewModel {
        lateinit var result: AuthViewModel
        compose.runOnUiThread {
            result = ViewModelProvider(compose.activity)[AuthViewModel::class.java]
        }
        return result
    }

    /** Waits for the actual reducer result; rendering never starts a request on its own. */
    private fun waitForScreen(screen: AuthScreen) {
        compose.waitUntil(20000) {
            viewModel().uiState.value.screen == screen && !viewModel().uiState.value.isProcessing
        }
        compose.waitForIdle()
    }
}

/** Synthetic local-only test identity; values are never written to source fixtures or logs. */
private class LocalTestAccount(
    val kind: AuthContactType,
    val method: AuthMethod,
    val contact: String,
    val password: String,
) {
    var resendAt: Long = 0
    val canonicalContact: String
        get() = if (kind == AuthContactType.Phone) "+234" + contact.drop(1) else contact

    override fun toString() = "LocalTestAccount(fields=redacted)"
}
