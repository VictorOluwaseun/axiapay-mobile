# AxiaPay Android

A native Jetpack Compose app with authenticated wallet display and everyday payment previews.

## Run

Open this folder in Android Studio, select `devDebug` or `demoDebug` in **Build Variants**, and run the `app` configuration on an Android 8.0+ device or emulator. The existing project uses Android SDK 37, Gradle 9.6.0 and the Gradle-managed Java 25 toolchain.

```sh
./gradlew :app:assembleDevDebug
./gradlew :app:verifyDev
# Verify formatting, unit tests, lint and debug/release/test APKs for every flavor:
./gradlew verifyAllFlavors
ANDROID_SERIAL=emulator-5554 ./gradlew :app:connectedDemoDebugAndroidTest

# Build the four debug APKs together.
./gradlew :app:assembleDebug
```

## Environments

| Flavor | Application ID | Launcher name | Long-term purpose |
| --- | --- | --- | --- |
| `dev` | `com.kesofty.axiapay.dev` | AxiaPay Dev | Development backend integrations |
| `demo` | `com.kesofty.axiapay.demo` | AxiaPay Demo | Permanent sample experience |
| `staging` | `com.kesofty.axiapay.staging` | AxiaPay Staging | Pre-production backend validation |
| `prod` | `com.kesofty.axiapay` | AxiaPay | Production backend integrations |

**Authentication and account/Home wallet balances use the API in dev/staging/prod; demo keeps simulated authentication and balances.** Payments, schedules and SMS remain labelled local previews. Staging/prod require an explicitly configured HTTPS API; no deployed address is assumed.

The four apps can be installed together, each with a separate Android sandbox, saved session, drafts and preferences. The unsuffixed ID is preserved for prod; existing installs using that ID belong to prod, and demo begins as a separate install. Kotlin packages and the resource namespace remain `com.kesofty.axiapay`.

Each flavor has `Debug` and `Release` variants, such as `demoDebug` and `prodRelease`. A flavor uses the same application ID for both build types, so debug and release of that same flavor do not install side by side. Flavor selects the environment; build type controls debugging and optimization. Release signing has not been configured.

Debug APKs are written to `app/build/outputs/apk/<flavor>/debug/`. Replace `Dev` in the commands above with `Demo`, `Staging` or `Prod` for other flavors. Use explicit flavor task names for unit tests, lint and device tests.

Hilt modules in `app/src/demo/java/` select the demo repositories and payment/SMS actions. Separate modules in `app/src/nonDemo/java/` are compiled into dev/staging/prod only. `FlavorAuthenticationModule` selects real account access, while `FlavorPaymentModule` retains payment simulations. Shared modules provide platform adapters, presentation collaborators and framework-independent use cases. This keeps one UI and sample implementation while giving future backend integrations a separate binding boundary. Environment selection stays at composition; the confirmed local dev URL is configured separately from undeclared staging/prod URLs. Credentials never belong in Gradle properties or source.

Source-set and variant conventions follow the [Android build-variant guide](https://developer.android.com/build/build-variants).

## Explore

- **Home:** wallet balance visibility, funding and sending, eight service shortcuts, upcoming plan and recent activity.
- **Payments:** searchable service directory, airtime/data/electricity/TV forms, batch-payment preview and SMS outbox.
- **Activity:** money-in, money-out and pending filters; individual receipts; completion of waiting payments after adding demo funds.
- **Plans:** create a dated one-time, daily, weekly or monthly payment; preview the next three dates; pause/resume plans; and change the demo reminder preference.
- **Notifications and profile:** sample renewal notices, waiting-payment prompts, and balance/reminder controls.

Airtime, data, electricity, TV, wallet funding and wallet/bank transfers have interactive form → review → receipt flows. Fields are validated locally. Payment providers, recipients, prices and simulated payment balances are illustrative. Non-demo account and Home wallet balances come from the API; real bank and carrier purchases are not wired into these forms. `ACCESS_NETWORK_STATE` observes Android connectivity without a runtime permission prompt. Authentication and wallet lookup send API requests; SMS remains simulated.

Education, insurance, airtime conversion and merchant payments have service previews. Offline mode has an interactive SMS simulation; internet detection is real, while SMS transport and durable outbox storage are not implemented. Scheduled execution, push notifications and actual airtime/data monitoring are not implemented.

Recurring plans keep their original calendar anchor: a plan starting on 31 January previews 28/29 February and 31 March. The chosen frequency is included in payment review, receipts and Plans, and survives activity recreation. Creating a recurring plan does not debit the wallet or launch a background payment.

The demo repository stores immutable session snapshots with integer NGN kobo. Payment use cases avoid debiting insufficient-funds requests and complete waiting requests only on an explicit action. Stable request IDs prevent repeated confirmation, restoration, or bulk retry from charging the same logical request twice. App-private atomic checkpoints restore the session, form and navigation after Android recreation. `SavedStateHandle` retains only small checkpoint identities; these files are demo snapshots, not a financial ledger.

## Offline SMS preview

Offline mode now follows Android’s validated internet connection automatically. Disconnect Wi-Fi and mobile data, or connect to a network without validated internet: the same screens, icons and controls become greyscale. When validated internet returns, the original colours return automatically. Automatic is the default. A persistent offline or manual-preview banner opens the outbox and connection controls.

**Temporary toggle:** tap **Home avatar → Connection preview**, or **Manage** in the banner. Choose **Force offline** for greyscale and the SMS demo, **Force online** for the coloured online demo, or **Automatic** to immediately follow the current real connection again. Forced modes are clearly labelled and persist through recreation for this demo session. They do not change device radios, send messages, or submit pending payments.

Open the SMS card at the bottom of Payments or **Home avatar → Open SMS outbox** to inspect pending requests. Automatic and manual mode changes preserve open form fields, saved drafts, request IDs, selected SIMs, plans and pending payment states. Reconnecting never sends an SMS, resubmits a payment or automatically confirms a result.

1. Open Airtime or Data, fill the familiar form, then review the payment and select a sample SIM.
2. **Review SMS request** saves the immutable request in the session outbox. Check the recipient/product, NGN maximum debit including the demo payment fee, gateway route and 15-minute send deadline.
3. **Send demo SMS** simulates transport only. **Try a failed-send scenario** demonstrates a failed attempt and an explicit retry with the same request ID, terms and SIM (maximum three attempts).
4. A sent request remains **Awaiting confirmation**. Activity and the outbox retain it across recreation. Reconnect, then **Check demo status**, or use **Simulate backend reply** offline. Only this explicit simulated backend result changes demo wallet funds. An insufficient-funds result waits for explicit completion after funding.

Other services and new schedules can be saved as a form draft and resumed after reconnecting. Existing schedules remain intact; changing accepted plans, funding, transfers and bulk execution need online mode, while payment execution still uses demo data. Force online only enables that demo path and does not provide real internet access. One in-progress form draft is retained, alongside the SMS outbox; both use private checkpoints tied to the existing task/session identity, not a production durable outbox.

No SMS is sent and no SMS permission is requested. No gateway phone number, wire format, enrollment/security scheme or verified reply channel is invented. The route and SIMs are sample data; carrier SMS charges are separate from the demo payment limit. The backend must authorize and settle real payments. Missing acknowledgement is not a failed payment, and reusing an SMS request through the online submission path is rejected in favour of reconciliation.

To verify automatic mode on an emulator, disable **both** Wi-Fi and mobile data (one can be a fallback for the other), then restore either connection. Merely having Wi-Fi turned on is not sufficient: Android must report validated internet. Android tests make these radio changes only on emulators and restore the prior settings afterward. Existing saved-state session limits still apply; this change does not add disk persistence.

## Architecture

The app follows Clean Architecture, MVVM with MVI, and Hilt dependency injection. See [ARCHITECTURE.md](ARCHITECTURE.md) for boundaries, request/effect delivery, persistence, and extension guidance.

| Package | Responsibility |
| --- | --- |
| `domain` | Android-independent payment models, exact-amount and recurrence policies, demo payment transitions, repository contract, explicit errors |
| `application` | Validation/preparation, submission, waiting-payment completion, plan/preferences actions, bulk validation/queue/run/retry, and observing the session |
| `data` | Original demo fixtures, atomic session repository and private checkpoint adapters |
| `presentation` | Immutable screen/form state, typed intents, form reducer, ViewModel and navigation restoration |
| `ui` | Stateless Compose screens, sheets, formatting, icons and theme |
| `di` | Hilt modules for platform dependencies, use cases, presentation collaborators and flavor bindings |

`ArchitectureTest` guards dependency direction and prevents mutable application state from returning to Views. Unit tests cover use cases, concurrency, persistence failure recovery, and MVI transitions. Device tests cover user flows, recreation, and snapshot serialization. Run `./gradlew formatKotlin` to apply the pinned ktfmt 0.65 style. `checkKotlinFormat` checks all Kotlin source sets (including shared non-demo wiring and Android tests) and Gradle scripts. `verifyAllFlavors` runs this check, unit tests, lint, debug/release builds and instrumentation APK compilation. Device test execution remains a separate emulator command. The GitHub Actions workflow runs a verification matrix for dev/demo/staging/prod without publishing or signing releases.

Payment actions use suspending application contracts and ViewModel-owned coroutines. An in-flight action shows progress and rejects repeated submissions; later results update the session without taking over a newly opened screen. Cancellation is not reported as payment failure, and restored state cannot replay a command. Repository snapshots own and protect their lists against external mutation. All four flavors still use the same demo operations.

Run `./gradlew :app:compileDemoReleaseKotlin -PcomposeReports=true --rerun-tasks` to generate Compose compiler stability/skipping reports in `app/build/reports/compose/` and `compose-metrics/`. Use them alongside device profiling; they do not replace runtime performance measurements.

## Visual system

Online: deep pine (#103F38), mint (#C5F2D8), soft white (#F6F8F7), peach (#FBE8D8), lavender (#ECE8F7), and ink (#173D35). Bundled variable Manrope typography uses explicit weight axes. The wallet is the principal visual anchor; quiet service tiles and unboxed transaction rows keep the rest readable. Vector icons and the launcher mark are drawn locally. The Manrope license is bundled in `app/src/main/assets/licenses`.

Offline: palette tokens and inline accents resolve to their greyscale luminance, including dialogs, controls and the native date picker. Selected and enabled controls retain contrast; typography and layout stay unchanged.

`ui/AxiaApp.kt` contains the four main screens, `ui/PaymentSheet.kt` renders payment forms/reviews, `ui/OfflineSheets.kt` renders connection/outbox/SMS states, `ui/Sheets.kt` contains the other sheets, `ui/components` contains the shared controls and vector icons, and `ui/theme` defines the palette and typography. `AxiaHomePreview` provides an Android Studio Compose preview.

## Account access

The existing phone/email and password/code selectors now drive all four real auth flows in **dev, staging and prod**:

- Signup with a password requests a signup challenge, then verifies it to create the account/session.
- Signup without a password requests and verifies the same contact-bound signup proof.
- Password sign-in exchanges the registered contact and exact password for a bearer session.
- Code sign-in requests a login-purpose challenge and verifies its ID/code without registering unknown contacts.

Local Nigerian numbers such as `080…` are sent as `+234…`; international E.164 numbers are accepted. Emails are trimmed/lowercased while preserving dots and plus aliases. New passwords use the API's 15–128 Unicode-code-point rule. Existing-password sign-in accepts 1–128 code points. Whitespace and case are never modified. The API accepts no signup name, so that field remains demo-only. Client checks improve feedback; server checks remain authoritative.

`AuthViewModel` calls the framework-independent `AccountAccess` application operations. The backend workflow uses `AuthenticationRepository` and `AuthenticationSessionStore`. The HTTP adapter sends only the published DTO fields, keeps challenge purpose/identity and leading code zeros, observes server expiry/resend timing and `Retry-After`, rejects malformed responses, and disables automatic retries, redirects and caching. Network failures never become demo success. A lost successful redemption response needs a fresh sign-in; the app does not replay it automatically.

Resending replaces the challenge ID. For password signup, the current API requires the password on each signup request; the app returns to the populated form and asks for the password again instead of retaining it after entering verification. Code-only signup and code login resend without a password. No code is auto-submitted.

Successful real auth displays the verified account and its wallet. **Open dashboard** shows the same account-owned wallet balance on Home; **Profile → Account & sign out** returns to account details. Other payment tools retain their independent, labelled previews. Tokens never enter UI state or `SavedStateHandle`. The token, expiry and normalized API identity are atomically encrypted with AES-GCM using Android Keystore and stored under `noBackupFilesDir`. API identity changes, expiry, unreadable ciphertext or lost keys require fresh access; no plaintext fallback is used. Startup/foreground checks call `/auth/me` before showing real identity. A connectivity failure preserves the protected record and presents explicit recovery, with the existing payment preview still available. Sign-out removes local access after server revocation; failure remains retryable. Cleanup compares the original protected record so a late logout or invalid-session response cannot delete a newer login. Temporary Keystore/IO failures retain ciphertext for explicit retry. There is no refresh-token endpoint or client refresh loop.

Only the preview-entry navigation Boolean is saved. Form contacts, names, passwords, challenges and codes stay in memory across configuration changes, and unfinished forms restart at Welcome after process restoration. Auth state and the payment owner's drafts/pending requests remain independent. The offline greyscale palette and temporary connection overrides are preserved. Real code delivery, password reset and biometric/device enrollment are separate backend capabilities; this change integrates the four existing entry flows and bearer-session lifecycle, not fingerprint or recovery screens.

### Local development

Start the existing local API and its development services from the backend repository. The dev flavor defaults to `http://10.0.2.2:4000/v1/` for the Android emulator. Its network policy permits HTTP only for emulator/loopback hosts; public HTTP, user-supplied credentials in URLs, query strings, fragments and redirecting auth endpoints are rejected. Staging/prod require HTTPS with normal platform trust.

Local email and phone codes are captured in **Mailpit at http://127.0.0.1:58025**. Phone delivery appears as mail to `sms+<international digits>@sms.axiapay.test`; no SMS is sent to a handset. Use the latest code for the corresponding challenge.

Override a base URL, including the `/v1/` prefix and trailing slash, through untracked user Gradle properties or `-P`:

```sh
./gradlew :app:assembleDevDebug -Paxiapay.dev.apiBaseUrl=http://10.0.2.2:4000/v1/
./gradlew :app:assembleStagingDebug -Paxiapay.staging.apiBaseUrl=https://your-staging-host/v1/
./gradlew :app:assembleProdRelease -Paxiapay.prod.apiBaseUrl=https://your-production-host/v1/
```

The HTTPS host names above are examples, not configured deployments. For an explicitly selected USB device, use `adb -s <serial> reverse tcp:4000 tcp:4000` and a dev override of `http://127.0.0.1:4000/v1/`. The application IDs remain distinct; credentials cannot cross installed flavors.

The demo flavor retains its original name field, eight-character sample-password policy, any-six-digits verification and simulated completion. **Explore demo** never establishes a real identity. No backend outage is hidden by switching to that simulation.

### Auth verification

`verifyAllFlavors` checks all debug/release/test APKs, generated Hilt graphs, lint, formatting and unit suites. The new unit tests exercise all contacts/methods, HTTP shapes, Unicode/exact passwords, leading zeros, purpose-bound proof, cooldowns, storage/revocation failure, cancellation, stale results, API isolation and session recovery. Android storage tests use isolated real Keystore records, test tampering/recreation and verify network policy.

Run the real local API UI check only on an explicitly selected dev emulator without an existing auth session:

```sh
adb -s emulator-5554 shell am instrument -w -r \
  -e runLocalAuth true \
  -e class com.kesofty.axiapay.ServerAuthFlowUiTest,com.kesofty.axiapay.AuthenticationSessionStorageTest,com.kesofty.axiapay.HiltGraphTest \
  com.kesofty.axiapay.dev.test/androidx.test.runner.AndroidJUnitRunner
```

This opt-in test creates synthetic local accounts through the real API, reads only matching Mailpit messages, checks phone/email signup with/without passwords, both sign-in methods, Activity recreation, and server-confirmed logout. It honors the real resend cooldown and never logs credentials/codes. Ordinary demo UI checks remain on the demo APK; non-demo instrumented APK compilation does not imply real-server test execution.

HTTP dependencies are pinned to OkHttp/OkHttp Coroutines **5.3.0**, whose published sources use coroutines 1.10.2 and Kotlin 2.2.x, compatible with this app's existing Kotlin/coroutines toolchain. JSON uses the Android platform implementation, with the pinned reference JSON library only in JVM tests. No additional DI or serialization plugin is required. References: [OkHttp 5.3.0 sources](https://github.com/lysine-dev/okhttp/tree/parent-5.3.0), [Android Keystore](https://developer.android.com/privacy-and-security/keystore), [Android no-backup storage](https://developer.android.com/reference/android/content/Context#getNoBackupFilesDir()).

## Hilt wiring

`AxiaPayApplication` owns the Hilt application component. `MainActivity` is an Android entry point and obtains `@HiltViewModel` owners through its default `ViewModelProvider`. The hand-written production factories have been removed. Domain/application classes remain plain Kotlin; shared modules provide them without DI annotations. Unit tests continue to construct controlled collaborators directly.

The clock and application-context connectivity adapter are application-scoped. Each payment ViewModel receives one `@ViewModelScoped` session repository and navigation store with checkpoint identities in its own Hilt-supplied `SavedStateHandle`. Auth retains its separate owner and handle. No mutable wallet session becomes a singleton.

Hilt runtime/compiler/plugin **2.60.1** and KSP **2.3.12** are pinned in the version catalog. The project keeps AGP 9.4.1 built-in Kotlin and the existing Compose compiler plugin; Java/Kotlin bytecode target 17 is used for the Hilt toolchain. `verifyAllFlavors` also compiles the generated Hilt graphs. `HiltGraphTest` exercises repository sharing within an owner, isolation across owners, and recreation against each installed flavor's actual graph, with the ordinary Android test runner. No separate Hilt test graph or test-only bindings are needed for these regression flows.

Reference: [Hilt Gradle setup](https://dagger.dev/hilt/gradle-setup), [Hilt ViewModel scopes](https://dagger.dev/hilt/view-model), [AGP built-in Kotlin](https://developer.android.com/build/migrate-to-built-in-kotlin).


## Bulk payment preview

Payments opens a service picker for Airtime, Data, Electricity or TV. Each quick list contains one payment type: enter a recipient, tap **+ Add another recipient**, and continue on the same screen. The next row reuses the provider and amount or plan, while identifiers stay blank. No batch name or separate setup is required. Inline edits update the running total; review includes the final entered row and ignores only an unused new row. Immediate individual payment forms offer the same add-recipient action, carrying the current form into the matching list. Try demo list adds four same-service examples. Each list supports up to 1,000 recipients. Data and TV quotes are illustrative, with no provider purchase. Review shows the demo wallet reservation and offers funding when needed. The optional failure scenario declines the last recipient once, releases its reservation and enables a separately reviewed failed-only retry. Activity’s Batches filter opens saved per-recipient progress, including two sample histories.

Independent service drafts, inline editor input, logical request IDs, reservations and outcomes use private, atomic demo checkpoints with small identities in SavedStateHandle. Leaving the screen does not interrupt an active run. Cancellation or losing internet retains queued/processing rows; restoration and reconnection never submit or resume them automatically. Use Resume demo explicitly. Bulk SMS, durable background execution and live providers are not implemented. History is bounded to 20 batches for this local demo; it is not a production storage or throughput limit. All four flavors retain this simulation through their existing Hilt composition boundary.

Named reusable groups and group membership are deferred product work; quick bulk payment does not create a group.


## Import bulk recipients

In the **demo flavor only**, the import screen includes **Download CSV sample** and **Download Excel sample**. The standard files have three synthetic recipients for the selected service. **Download 1,000 recipients · CSV** generates a full-size synthetic list on the phone; airtime uses ₦100 per row so a fresh demo wallet can cover it. Data and TV samples retain their catalog prices and may require explicit demo funding. Android's Save dialog lets you put it in Downloads or another folder, then choose it for import. Samples are bundled in the demo APK and work offline. Downloading never adds recipients or submits a payment. Cancellation and save failures leave the current draft and preview intact. Dev, staging and prod contain neither this capability nor its sample assets.

Choose a service in Payments → Bulk payments, then **Import list**. CSV, modern Excel (`.xlsx`) and Excel 97–2003 (`.xls`) are read locally through Android’s document picker; no storage permission or upload is required. Excel uses the first worksheet in tab order. Files are limited to 2 MB, 1,000 recipient rows plus the header, 16 columns and 256 characters per cell; XLSX expansion is bounded to 8 MB. Password protection and formulas are unsupported.

Required headings (case-insensitive; column order is flexible):

| Service | Columns |
| --- | --- |
| Airtime | `recipient`, `provider`, `amount` |
| Data / TV | `recipient`, `provider`, `product` |
| Electricity | `recipient`, `provider`, `product`, `amount` |

`name` is optional; `phone`, `meter`, `smartcard`, `network`, `plan` and `account_type` are accepted aliases. Optional `service` must match the chosen list. Use the demo provider/product choices shown in the import guide. Amounts are naira decimals without currency symbols or thousands separators; supplied fixed-product amounts must match the demo quote. CSV supports comma separators, quoted fields, UTF-8 and BOM-marked UTF-16. In Excel, enter account identifiers as **Text** including the leading zero: numeric identifiers are flagged instead of guessed.

The preview shows source row numbers, valid entries, duplicates and actionable errors. Select the valid rows to add; invalid and unselected rows are excluded explicitly. Selection must fit the existing 1,000-recipient list, including unfinished input. Imports preserve manual recipients and the current editor, and never submit a payment. Local files can be imported offline; cloud-only documents may need downloading first. Rotation retains the preview; process recreation retains the draft and returns to recipient entry without reopening a file grant. The original file and preview are not persisted or uploaded.


## Large-batch verification

Recipient entry has a pinned search field, compact lazy rows with stable IDs, multi-selection and confirmed removal. Search never changes the full-list total. Imports have ready/needs-attention/selected filters and explicit select-available/clear actions. Review and progress can search the full batch; every recipient retains its outcome and attempt identity. Large demos advance in groups of up to 25, with atomic reservation/refund checkpoints rather than a save per recipient.

`BulkScaleTest` covers all four services at 1,000 rows, overflow, exact totals, last-row editing and interrupted/resumed execution. `RecipientDocumentParserTest` exercises 1,000 and 1,001 rows in CSV, XLSX and XLS. `BulkScaleDeviceTest` covers the full Hilt/UI/import/offline/retry path and asserts the lazy viewport does not compose the whole list. `BulkCheckpointTest` exercises large draft/reservation restoration, small saved-state payloads, write failure, corrupt-read recovery and cancellation during commit.

The JVM preparation probe measured 1,000 rows at approximately 458 ms before linear duplicate checks and 10 ms afterward on this development host. These are diagnostic CPU measurements, not phone frame-rate guarantees. Device checks use an explicitly selected emulator; production throughput still depends on future backend and provider integrations.


### Signed-in wallet lookup

For reproducible verification with the current toolchain, use `./gradlew verifyAllFlavors --max-workers=1`; this avoids the observed Kotlin lint analyzer crash during concurrent flavor analysis. Device checks passed on the API 36 emulator. The installed Espresso version failed before app interaction on the API 37 image, so use API 36 for these checks.

Non-demo authentication now fetches `GET /v1/wallets/me?currency=NGN` with the newly issued bearer after protected session persistence, for password login, code login and successful signup verification. Stored-session restoration also reloads the wallet. The account screen displays its server wallet ID, status and exact available/reserved/total balances. HTTP transport is shared with authentication; it remains bounded, cancellable, uncached on-device and configured to disable redirects and automatic retries.

A missing wallet (404), network outage or malformed response produces an explicit wallet state while keeping the verified account signed in. **Retry wallet** rechecks the stored session and repeats the GET; it never replays signup, login or code redemption and never calls create-wallet. A wallet 401 removes only the captured invalid session. API-scope checks, credential-store comparisons, cancellation and ViewModel visit ownership prevent old results crossing a newer session or navigation. Wallet values and bearer credentials are excluded from saved UI state.

The real wallet is separate from the payment preview, which still uses its own demo ledger. The demo flavor remains fully simulated and receives no wallet HTTP repository. The API's Redis caching policy stays server-side; the mobile wallet is display data and never authorizes spending.

`HttpWalletRepositoryTest` and `AccountWalletTest` cover the GET/header contract, exact large amounts, failed lookup, no create fallback, login/verification ordering, protected persistence failure, API/session isolation, retries and late cancellation. The opt-in `ServerAuthFlowUiTest` now asserts the real NGN wallet after every supported login/signup flow and Activity recreation.


### Home wallet source

The non-demo Home dashboard now renders the authenticated account's NGN wallet available balance. It receives an immutable projection from `AuthViewModel`; the demo payment ledger never supplies or mutates that amount. The signed-in account page opens Home through **Open dashboard**. Home offers wallet refresh and account actions, preserves balance hiding and the offline greyscale palette, and reloads the verified wallet after restoration. Loading, sign-in, missing-wallet and network-failure states contain no sample or invented zero balance.

The demo flavor keeps its original sample balance, funding/transfer controls and saved-state simulation. Other payment, plan and activity tools are still labelled previews while their integrations are pending. UI suites that assert simulated wallet amounts run only in the demo flavor; real Home coverage uses account-owned wallet fixtures and the opt-in dev API flow. A real wallet balance is display data, and all spending remains server-authoritative. `HomeWalletStateTest`, `AccountWalletTest`, `HomeWalletUiTest` and the opt-in real auth flow cover selection, isolation, refresh, logout/account changes, restoration, failure recovery, privacy and accessibility.
