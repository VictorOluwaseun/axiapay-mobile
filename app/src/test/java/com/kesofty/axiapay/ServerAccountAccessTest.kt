package com.kesofty.axiapay

import com.kesofty.axiapay.application.auth.ServerAccountAccess
import com.kesofty.axiapay.domain.*
import com.kesofty.axiapay.domain.auth.*
import java.time.*
import kotlinx.coroutines.*
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

/**
 * Checks real auth coordination, exact credentials and recovery without network or Android storage.
 */
class ServerAccountAccessTest {
    private val now = Instant.parse("2026-10-16T12:00:00Z")
    private val clock = Clock.fixed(now, ZoneOffset.UTC)

    /**
     * All signup methods and contact kinds require a server challenge; code flows omit passwords.
     */
    @Test
    fun signupAndCodeLoginUseNormalizedContactsAndPurposeBoundChallenges() = runTest {
        for (kind in AuthContactType.entries) {
            for (method in AuthMethod.entries) {
                val api = RecordingAuthenticationRepository(now)
                val store = MemoryAuthenticationSessionStore()
                val access =
                    ServerAccountAccess(
                        api,
                        store,
                        clock,
                        RecordingWalletRepository(),
                        dashboard = InMemoryDashboardRepository(),
                    )
                val value =
                    if (kind == AuthContactType.Phone) "0801 234 5678" else " Ada+tag@Example.test "
                val input =
                    AccountAccessInput(
                        kind,
                        value,
                        method,
                        " exact passphrase ",
                        isCreatingAccount = true,
                    )
                val result = access.begin(input) as AccountAccessResult.CodeRequired
                assertEquals(
                    if (kind == AuthContactType.Phone) "+2348012345678" else "ada+tag@example.test",
                    api.contact?.value,
                )
                assertEquals(AuthenticationPurpose.Signup, result.challenge?.purpose)
                assertEquals(method == AuthMethod.Password, result.challenge?.usesPassword)
                assertEquals(
                    if (method == AuthMethod.Password) " exact passphrase " else null,
                    api.password,
                )
                assertNull(store.value)
                val signedIn =
                    access.verify(result.challenge, "001234") as AccountAccessResult.SignedIn
                assertEquals("001234", api.code)
                assertEquals(api.session.account, signedIn.account)
                assertEquals(api.session.accessToken, store.value?.accessToken)
                assertEquals(api.apiIdentity, store.value?.apiIdentity)
            }
        }
        val api = RecordingAuthenticationRepository(now)
        val access =
            ServerAccountAccess(
                api,
                MemoryAuthenticationSessionStore(),
                clock,
                RecordingWalletRepository(),
                dashboard = InMemoryDashboardRepository(),
            )
        val result =
            access.begin(
                AccountAccessInput(
                    AuthContactType.Email,
                    "a@example.test",
                    AuthMethod.OneTimeCode,
                    password = "stale hidden password",
                ),
            ) as AccountAccessResult.CodeRequired
        assertEquals(AuthenticationPurpose.Login, result.challenge?.purpose)
        assertNull(api.password)
    }

    /**
     * Existing passwords are not subjected to signup minimums or trimming; Unicode counts as code
     * points.
     */
    @Test
    fun passwordSigninIsDirectAndSignupUsesCodePointLength() = runTest {
        val api = RecordingAuthenticationRepository(now)
        val store = MemoryAuthenticationSessionStore()
        val access =
            ServerAccountAccess(
                api,
                store,
                clock,
                RecordingWalletRepository(),
                dashboard = InMemoryDashboardRepository(),
            )
        assertTrue(
            access.begin(
                AccountAccessInput(
                    AuthContactType.Email,
                    "ada@example.test",
                    AuthMethod.Password,
                    " x ",
                ),
            ) is AccountAccessResult.SignedIn,
        )
        assertEquals(" x ", api.password)
        assertEquals(0, api.codeRequests)
        val signup =
            AccountAccessInput(
                AuthContactType.Phone,
                "+2348012345678",
                AuthMethod.Password,
                "😀".repeat(7),
                isCreatingAccount = true,
            )
        assertEquals(
            AccountAccessResult.Rejected(AccountAccessIssue.Password),
            access.begin(signup),
        )
        assertTrue(
            access.begin(signup.copy(password = "😀".repeat(8)))
                is AccountAccessResult.CodeRequired,
        )
        assertEquals("😀".repeat(8), api.password)
        assertEquals(
            AccountAccessResult.Rejected(AccountAccessIssue.Password),
            access.begin(signup.copy(password = "a".repeat(129))),
        )
    }

    /** Eight-character signup passwords reach the API exactly; seven characters stop before IO. */
    @Test
    fun signupAcceptsEightCharactersWithoutChangingWhitespace() = runTest {
        val api = RecordingAuthenticationRepository(now)
        val access =
            ServerAccountAccess(
                api,
                MemoryAuthenticationSessionStore(),
                clock,
                RecordingWalletRepository(),
                dashboard = InMemoryDashboardRepository(),
            )
        val signup =
            AccountAccessInput(
                AuthContactType.Email,
                "ada@example.test",
                AuthMethod.Password,
                "1234567",
                isCreatingAccount = true,
            )
        assertEquals(
            AccountAccessResult.Rejected(AccountAccessIssue.Password),
            access.begin(signup),
        )
        assertEquals(0, api.codeRequests)
        for (password in listOf("12345678", " 123456 ")) {
            assertTrue(
                access.begin(signup.copy(password = password)) is AccountAccessResult.CodeRequired,
            )
            assertEquals(password, api.password)
        }
    }

    /**
     * Expired/missing proof and resends cannot silently convert a password signup to passwordless.
     */
    @Test
    fun resendRetainsPurposeAndRequiresPasswordReentryForPasswordSignup() = runTest {
        val api = RecordingAuthenticationRepository(now)
        val access =
            ServerAccountAccess(
                api,
                MemoryAuthenticationSessionStore(),
                clock,
                RecordingWalletRepository(),
                dashboard = InMemoryDashboardRepository(),
            )
        val input =
            AccountAccessInput(
                AuthContactType.Email,
                "ada@example.test",
                AuthMethod.Password,
                isCreatingAccount = true,
            )
        val challenge =
            AuthenticationChallenge(
                "challenge",
                AuthenticationPurpose.Signup,
                AccountContact(AuthContactType.Email, "ada@example.test"),
                true,
                now.plusSeconds(300),
                now.minusSeconds(1),
            )
        assertEquals(
            AccountAccessResult.Rejected(AccountAccessIssue.SignupPasswordRequired),
            access.resend(challenge, input),
        )
        assertEquals(0, api.codeRequests)
        assertEquals(
            AccountAccessResult.Rejected(AccountAccessIssue.RateLimited, 60),
            access.resend(challenge.copy(resendAt = now.plusSeconds(60)), input),
        )
        val resent =
            access.resend(challenge.copy(usesPassword = false), input)
                as AccountAccessResult.CodeRequired
        assertEquals(AuthenticationPurpose.Signup, resent.challenge?.purpose)
        assertEquals("challenge-1", resent.challenge?.id)
        assertEquals(
            AccountAccessResult.Rejected(AccountAccessIssue.CodeExpired),
            access.verify(challenge.copy(expiresAt = now), "001234"),
        )
        assertEquals(
            AccountAccessResult.Rejected(AccountAccessIssue.CodeExpired),
            access.verify(null, "001234"),
        )
        assertNull(api.code)
    }

    /**
     * Network recovery retains protected credentials; authoritative invalidation and expiry remove
     * them.
     */
    @Test
    fun restorationNeverFallsBackToDemoOrSendsCredentialsToAnotherApi() = runTest {
        val api = RecordingAuthenticationRepository(now)
        val store =
            MemoryAuthenticationSessionStore(
                StoredAuthenticationSession(
                    api.session.accessToken,
                    now.plusSeconds(3600),
                    api.apiIdentity,
                ),
            )
        val access =
            ServerAccountAccess(
                api,
                store,
                clock,
                RecordingWalletRepository(),
                dashboard = InMemoryDashboardRepository(),
            )
        api.failure = AuthenticationFailure(AccountAccessIssue.Network)
        try {
            access.restore()
            fail("Network failure must remain explicit")
        } catch (failure: AuthenticationFailure) {
            assertEquals(AccountAccessIssue.Network, failure.issue)
        }
        assertNotNull(store.value)
        api.failure = null
        assertEquals(
            AccountAccessResult.SignedIn(
                api.session.account,
                com.kesofty.axiapay.domain.wallet.WalletLookupResult.Loaded(
                    RecordingWalletRepository().wallet,
                ),
            ),
            access.restore(),
        )
        api.failure = AuthenticationFailure(AccountAccessIssue.InvalidCredentials)
        assertEquals(AccountAccessResult.SignedOut, access.restore())
        assertNull(store.value)
        store.value =
            StoredAuthenticationSession(
                "old-token",
                now.plusSeconds(3600),
                "https://other.example/v1/",
            )
        val calls = api.identityRequests
        assertEquals(AccountAccessResult.SignedOut, access.restore())
        assertEquals(calls, api.identityRequests)
        store.value = StoredAuthenticationSession("old-token", now, api.apiIdentity)
        assertEquals(AccountAccessResult.SignedOut, access.restore())
        assertEquals(calls, api.identityRequests)
    }

    /** Persistence and revocation failures must not be presented as successful sign-in/sign-out. */
    @Test
    fun securePersistenceAndRevocationMustCompleteBeforeSuccess() = runTest {
        val api = RecordingAuthenticationRepository(now)
        val store = MemoryAuthenticationSessionStore().apply { writeFailure = true }
        val access =
            ServerAccountAccess(
                api,
                store,
                clock,
                RecordingWalletRepository(),
                dashboard = InMemoryDashboardRepository(),
            )
        try {
            access.begin(
                AccountAccessInput(
                    AuthContactType.Email,
                    "ada@example.test",
                    AuthMethod.Password,
                    "password",
                ),
            )
            fail()
        } catch (failure: AuthenticationFailure) {
            assertEquals(AccountAccessIssue.SecureStorage, failure.issue)
        }
        assertNull(store.value)
        store.writeFailure = false
        access.begin(
            AccountAccessInput(
                AuthContactType.Email,
                "ada@example.test",
                AuthMethod.Password,
                "password",
            ),
        )
        api.failure = AuthenticationFailure(AccountAccessIssue.Network)
        try {
            access.signOut()
            fail()
        } catch (failure: AuthenticationFailure) {
            assertEquals(AccountAccessIssue.Network, failure.issue)
        }
        assertNotNull(store.value)
        api.failure = null
        assertEquals(AccountAccessResult.SignedOut, access.signOut())
        assertNull(store.value)
        assertEquals(2, api.logoutRequests)
    }

    /**
     * A late revocation or invalid-session response cannot delete credentials from a newer login.
     */
    @Test
    fun lateSessionCleanupPreservesNewerCredentials() = runTest {
        val api = RecordingAuthenticationRepository(now)
        val old =
            StoredAuthenticationSession("a".repeat(43), now.plusSeconds(3600), api.apiIdentity)
        val newer = old.copy(accessToken = "b".repeat(43))
        val store = MemoryAuthenticationSessionStore(old)
        val access =
            ServerAccountAccess(
                api,
                store,
                clock,
                RecordingWalletRepository(),
                dashboard = InMemoryDashboardRepository(),
            )
        api.beforeIdentity = { store.value = newer }
        api.failure = AuthenticationFailure(AccountAccessIssue.InvalidCredentials)
        assertEquals(AccountAccessResult.SignedOut, access.restore())
        assertEquals(newer, store.value)
        store.value = old
        api.failure = null
        api.beforeLogout = { store.value = newer }
        access.signOut()
        assertEquals(newer, store.value)
    }

    /** A cancelled caller cannot persist a session returned by a cancellation-resistant adapter. */
    @Test
    fun lateCancelledLoginCannotPersistCredentials() = runTest {
        val api = RecordingAuthenticationRepository(now).apply { gate = CompletableDeferred() }
        val store = MemoryAuthenticationSessionStore()
        val access =
            ServerAccountAccess(
                api,
                store,
                clock,
                RecordingWalletRepository(),
                dashboard = InMemoryDashboardRepository(),
            )
        val job =
            launch(start = CoroutineStart.UNDISPATCHED) {
                access.begin(
                    AccountAccessInput(
                        AuthContactType.Email,
                        "ada@example.test",
                        AuthMethod.Password,
                        "password",
                    ),
                )
            }
        job.cancel()
        api.gate!!.complete(Unit)
        job.join()
        assertNull(store.value)
    }
}

/**
 * Deterministic remote port with observable calls and optional cancellation-resistant completion.
 */
internal class RecordingAuthenticationRepository(now: Instant) : AuthenticationRepository {
    override val apiIdentity = "https://api.example.test/v1/"
    var session =
        AuthenticatedSession(
            "a".repeat(43),
            now.plusSeconds(3600),
            AccountIdentity(
                "123e4567-e89b-42d3-a456-426614174000",
                AccountContact(AuthContactType.Email, "ada@example.test"),
                now,
            ),
        )
    var contact: AccountContact? = null
    var password: String? = null
    var code: String? = null
    var codeRequests = 0
    var passwordRequests = 0
    var verifyRequests = 0
    var beforeIdentity: (() -> Unit)? = null
    var beforeLogout: (() -> Unit)? = null
    var identityRequests = 0
    var logoutRequests = 0
    var failure: AuthenticationFailure? = null
    var gate: CompletableDeferred<Unit>? = null

    override suspend fun requestCode(
        contact: AccountContact,
        purpose: AuthenticationPurpose,
        password: String?,
    ): AuthenticationChallengeTerms {
        codeRequests++
        this.contact = contact
        this.password = password
        failure?.let { throw it }
        return AuthenticationChallengeTerms("challenge-$codeRequests", 300, 60)
    }

    override suspend fun loginWithPassword(
        contact: AccountContact,
        password: String,
    ): AuthenticatedSession {
        passwordRequests++
        this.contact = contact
        this.password = password
        gate?.let { withContext(NonCancellable) { it.await() } }
        failure?.let { throw it }
        return session
    }

    override suspend fun verifyCode(
        challenge: AuthenticationChallenge,
        code: String,
    ): AuthenticatedSession {
        verifyRequests++
        this.code = code
        failure?.let { throw it }
        return session
    }

    override suspend fun currentAccount(accessToken: String): AccountIdentity {
        identityRequests++
        beforeIdentity?.invoke()
        failure?.let { throw it }
        return session.account
    }

    override suspend fun logout(accessToken: String) {
        logoutRequests++
        beforeLogout?.invoke()
        failure?.let { throw it }
    }
}

/** In-memory credential port used only in isolated tests; it never touches Android preferences. */
internal class MemoryAuthenticationSessionStore(var value: StoredAuthenticationSession? = null) :
    AuthenticationSessionStore {
    var writeFailure = false

    override suspend fun read() = value

    override suspend fun write(session: StoredAuthenticationSession) {
        if (writeFailure) {
            throw AuthenticationFailure(AccountAccessIssue.SecureStorage)
        }
        value = session
    }

    /** Match the captured fixture record before publishing a replacement pair. */
    override suspend fun replace(
        expected: StoredAuthenticationSession,
        replacement: StoredAuthenticationSession,
    ): Boolean {
        if (value != expected) return false
        write(replacement)
        return true
    }

    override suspend fun clear(expected: StoredAuthenticationSession) {
        if (value == expected) {
            value = null
        }
    }
}
