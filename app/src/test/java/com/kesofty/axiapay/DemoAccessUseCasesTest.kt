package com.kesofty.axiapay

import com.kesofty.axiapay.application.*
import com.kesofty.axiapay.domain.*
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

/** Covers routing, input meaning and validation across all eight demo auth combinations. */
class DemoAccessUseCasesTest {
    /** Every signup verifies; password sign-in alone completes without a code step. */
    @Test
    fun `both contacts support all four auth flows`() = runTest {
        val repository = RecordingAccessRepository()
        val prepare = PrepareDemoAccess(repository)
        for (contactType in AuthContactType.entries) {
            for (method in AuthMethod.entries) {
                for (isCreatingAccount in listOf(false, true)) {
                    val contact =
                        if (contactType == AuthContactType.Phone) "+234 801 234 5678"
                        else " Ada+demo@Example.com "
                    val input =
                        DemoAccessInput(
                            contactType,
                            contact,
                            method,
                            " demo password ",
                            "Ada",
                            isCreatingAccount,
                        )
                    val expected =
                        if (!isCreatingAccount && method == AuthMethod.Password)
                            DemoAccessResult.PreviewComplete
                        else DemoAccessResult.CodeRequired
                    assertEquals(expected, prepare(input))
                    val submitted = requireNotNull(repository.lastInput)
                    assertEquals(
                        if (contactType == AuthContactType.Phone) "+2348012345678"
                        else "Ada+demo@Example.com",
                        submitted.contact,
                    )
                    assertEquals(
                        if (method == AuthMethod.Password) " demo password " else "",
                        submitted.password,
                    )
                }
            }
        }
        assertEquals(8, repository.preparations)
    }

    /** Invalid contacts never reach the adapter and email case remains intact after trimming. */
    @Test
    fun `invalid contacts do not request demo access`() = runTest {
        val repository = RecordingAccessRepository()
        val prepare = PrepareDemoAccess(repository)
        assertEquals(
            DemoAccessResult.InvalidPhone,
            prepare(DemoAccessInput(AuthContactType.Phone, "123", AuthMethod.OneTimeCode)),
        )
        for (email in
            listOf(
                "",
                "missing-at.example.com",
                "two@@example.com",
                "a@b",
                "a..b@example.com",
                "a@-example.com",
                "a@example..com",
                "a b@example.com",
                "a@exa mple.com",
            )) {
            assertEquals(
                email,
                DemoAccessResult.InvalidEmail,
                prepare(DemoAccessInput(AuthContactType.Email, email, AuthMethod.OneTimeCode)),
            )
        }
        assertEquals(0, repository.preparations)
        assertEquals(
            DemoAccessResult.CodeRequired,
            prepare(
                DemoAccessInput(AuthContactType.Email, " Ada@example.com ", AuthMethod.OneTimeCode),
            ),
        )
        assertEquals("Ada@example.com", repository.lastInput?.contact)
    }

    /** The demo minimum applies only to password methods; password characters are not trimmed. */
    @Test
    fun `signup name and password validation precede delegation`() = runTest {
        val repository = RecordingAccessRepository()
        val prepare = PrepareDemoAccess(repository)
        val signup =
            DemoAccessInput(
                AuthContactType.Email,
                "ada@example.com",
                AuthMethod.Password,
                "short",
                "Ada",
                true,
            )
        assertEquals(DemoAccessResult.InvalidName, prepare(signup.copy(name = " ")))
        assertEquals(DemoAccessResult.InvalidPassword, prepare(signup))
        assertEquals(
            DemoAccessResult.InvalidPassword,
            prepare(signup.copy(isCreatingAccount = false)),
        )
        assertEquals(DemoAccessResult.InvalidPassword, prepare(signup.copy(password = "        ")))
        assertEquals(0, repository.preparations)
        assertEquals(
            DemoAccessResult.CodeRequired,
            prepare(signup.copy(method = AuthMethod.OneTimeCode)),
        )
        assertEquals("", repository.lastInput?.password)
        assertEquals(
            DemoAccessResult.CodeRequired,
            prepare(signup.copy(password = " sample password ")),
        )
        assertEquals(" sample password ", repository.lastInput?.password)
    }

    /** A six-digit code is explicit and preserves leading zeros; malformed codes never delegate. */
    @Test
    fun `code completion preserves exact ASCII digits`() = runTest {
        val repository = RecordingAccessRepository()
        val complete = CompleteDemoAccess(repository)
        for (code in listOf("12345", "１２３４５６", "12 3456", "1234567")) {
            assertEquals(DemoAccessResult.InvalidCode, complete(code))
        }
        assertNull(repository.lastCode)
        assertEquals(DemoAccessResult.PreviewComplete, complete("001234"))
        assertEquals("001234", repository.lastCode)
    }

    /** Resend uses the contact boundary and has no dependency on a retained form password. */
    @Test
    fun `resend normalizes its destination without submitting a password`() = runTest {
        val repository = RecordingAccessRepository()
        val resend = ResendDemoAccessCode(repository)
        assertEquals(DemoAccessResult.InvalidEmail, resend(AuthContactType.Email, "invalid"))
        assertEquals(0, repository.resends)
        assertEquals(
            DemoAccessResult.CodeRequired,
            resend(AuthContactType.Email, " Ada@example.com "),
        )
        assertEquals("Ada@example.com", repository.lastResendContact)
        assertEquals(0, repository.preparations)
    }

    /** Interpolating a submitted form in diagnostics cannot print contact/name/password values. */
    @Test
    fun `submitted input description redacts synthetic credentials`() {
        val input =
            DemoAccessInput(
                AuthContactType.Email,
                "ada@example.com",
                AuthMethod.Password,
                "synthetic password",
                "Ada",
                true,
            )
        assertFalse(input.toString().contains("ada@example.com"))
        assertFalse(input.toString().contains("synthetic password"))
        assertFalse(input.toString().contains("Ada"))
    }
}
