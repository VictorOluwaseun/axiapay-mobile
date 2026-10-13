# AxiaPay Android

A native Jetpack Compose UI preview for everyday payments.

## Run

Open this folder in Android Studio, select `devDebug` or `demoDebug` in **Build Variants**, and run the `app` configuration on an Android 8.0+ device or emulator. The existing project uses Android SDK 37, Gradle 9.6.0 and the Gradle-managed Java 25 toolchain.

```sh
./gradlew :app:assembleDevDebug
./gradlew :app:testDevDebugUnitTest :app:lintDevDebug
ANDROID_SERIAL=emulator-5554 ./gradlew :app:connectedDevDebugAndroidTest

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

**Today all four use the current UI, sample data and local payment/SMS simulations.** Choosing `prod` does not connect real financial services. Backend replacements will arrive feature by feature for dev/staging/prod, while demo retains sample repositories and simulated operations. Existing demo labels stay until the corresponding feature actually uses real data.

The four apps can be installed together, each with a separate Android sandbox, saved session, drafts and preferences. The unsuffixed ID is preserved for prod; existing installs using that ID belong to prod, and demo begins as a separate install. Kotlin packages and the resource namespace remain `com.kesofty.axiapay`.

Each flavor has `Debug` and `Release` variants, such as `demoDebug` and `prodRelease`. A flavor uses the same application ID for both build types, so debug and release of that same flavor do not install side by side. Flavor selects the environment; build type controls debugging and optimization. Release signing has not been configured.

Debug APKs are written to `app/build/outputs/apk/<flavor>/debug/`. Replace `Dev` in the commands above with `Demo`, `Staging` or `Prod` for other flavors. Use explicit flavor task names for unit tests, lint and device tests.

The demo dependency factory lives in `app/src/demo/java/`; a separate factory in `app/src/nonDemo/java/` is compiled into dev/staging/prod only. Both currently delegate to `createDemoViewModel`. This keeps one shared UI and sample implementation while giving future backend integrations a separate composition boundary. Environment choices belong in this wiring layer, not the Views or domain; no backend URLs or credentials have been invented.

Source-set and variant conventions follow the [Android build-variant guide](https://developer.android.com/build/build-variants).

## Explore

- **Home:** wallet balance visibility, funding and sending, eight service shortcuts, upcoming plan and recent activity.
- **Payments:** searchable service directory, airtime/data/electricity/TV forms, batch-payment preview and SMS outbox.
- **Activity:** money-in, money-out and pending filters; individual receipts; completion of waiting payments after adding demo funds.
- **Plans:** create a dated one-time, daily, weekly or monthly payment; preview the next three dates; pause/resume plans; and change the demo reminder preference.
- **Notifications and profile:** sample renewal notices, waiting-payment prompts, and balance/reminder controls.

Airtime, data, electricity, TV, wallet funding and wallet/bank transfers have interactive form → review → receipt flows. Fields are validated locally. All providers, balances, recipients and prices are illustrative. No actual bank or carrier integration is present. `ACCESS_NETWORK_STATE` observes Android connectivity without a runtime permission prompt; the app still sends no network requests or SMS.

Education, insurance, airtime conversion and merchant payments have service previews. Offline mode has an interactive SMS simulation; internet detection is real, while SMS transport and durable outbox storage are not implemented. Scheduled execution, push notifications and actual airtime/data monitoring are not implemented.

Recurring plans keep their original calendar anchor: a plan starting on 31 January previews 28/29 February and 31 March. The chosen frequency is included in payment review, receipts and Plans, and survives activity recreation. Creating a recurring plan does not debit the wallet or launch a background payment.

The demo repository stores immutable session snapshots with integer NGN kobo. Payment use cases avoid debiting insufficient-funds requests and complete waiting requests only on an explicit action. Stable request IDs prevent repeated confirmation, restoration, or bulk retry from charging the same logical request twice. `SavedStateHandle` restores the session, form and navigation after Android recreation; this remains a demo session rather than a disk-backed financial ledger.

## Offline SMS preview

Offline mode now follows Android’s validated internet connection automatically. Disconnect Wi-Fi and mobile data, or connect to a network without validated internet: the same screens, icons and controls become greyscale. When validated internet returns, the original colours return automatically. Automatic is the default. A persistent offline or manual-preview banner opens the outbox and connection controls.

**Temporary toggle:** tap **Home avatar → Connection preview**, or **Manage** in the banner. Choose **Force offline** for greyscale and the SMS demo, **Force online** for the coloured online demo, or **Automatic** to immediately follow the current real connection again. Forced modes are clearly labelled and persist through recreation for this demo session. They do not change device radios, send messages, or submit pending payments.

Open the SMS card at the bottom of Payments or **Home avatar → Open SMS outbox** to inspect pending requests. Automatic and manual mode changes preserve open form fields, saved drafts, request IDs, selected SIMs, plans and pending payment states. Reconnecting never sends an SMS, resubmits a payment or automatically confirms a result.

1. Open Airtime or Data, fill the familiar form, then review the payment and select a sample SIM.
2. **Review SMS request** saves the immutable request in the session outbox. Check the recipient/product, NGN maximum debit including the demo payment fee, gateway route and 15-minute send deadline.
3. **Send demo SMS** simulates transport only. **Try a failed-send scenario** demonstrates a failed attempt and an explicit retry with the same request ID, terms and SIM (maximum three attempts).
4. A sent request remains **Awaiting confirmation**. Activity and the outbox retain it across recreation. Reconnect, then **Check demo status**, or use **Simulate backend reply** offline. Only this explicit simulated backend result changes demo wallet funds. An insufficient-funds result waits for explicit completion after funding.

Other services and new schedules can be saved as a form draft and resumed after reconnecting. Existing schedules remain intact; changing accepted plans, funding, transfers and bulk execution need online mode, while payment execution still uses demo data. Force online only enables that demo path and does not provide real internet access. One in-progress form draft is retained, alongside the SMS outbox; both use the existing saved-state session lifetime, not permanent disk storage.

No SMS is sent and no SMS permission is requested. No gateway phone number, wire format, enrollment/security scheme or verified reply channel is invented. The route and SIMs are sample data; carrier SMS charges are separate from the demo payment limit. The backend must authorize and settle real payments. Missing acknowledgement is not a failed payment, and reusing an SMS request through the online submission path is rejected in favour of reconciliation.

To verify automatic mode on an emulator, disable **both** Wi-Fi and mobile data (one can be a fallback for the other), then restore either connection. Merely having Wi-Fi turned on is not sufficient: Android must report validated internet. Android tests make these radio changes only on emulators and restore the prior settings afterward. Existing saved-state session limits still apply; this change does not add disk persistence.

## Architecture

The app follows Clean Architecture, MVVM with MVI, and explicit dependency injection. See [ARCHITECTURE.md](ARCHITECTURE.md) for boundaries, request/effect delivery, persistence, and extension guidance.

| Package | Responsibility |
| --- | --- |
| `domain` | Android-independent payment models, exact-amount and recurrence policies, demo payment transitions, repository contract, explicit errors |
| `application` | Validation/preparation, submission, waiting-payment completion, plan/preferences actions, batch retry, and observing the session |
| `data` | Original demo fixtures, atomic session repository and saved-state snapshot adapter |
| `presentation` | Immutable screen/form state, typed intents, form reducer, ViewModel and navigation restoration |
| `ui` | Stateless Compose screens, sheets, formatting, icons and theme |
| `di` | Factory that explicitly wires adapters and use cases into the ViewModel |

`ArchitectureTest` guards dependency direction and prevents mutable application state from returning to Views. Unit tests cover use cases, concurrency, persistence failure recovery, and MVI transitions. Device tests cover user flows, recreation, and snapshot serialization. Source was formatted with ktfmt 0.65 (`--kotlinlang-style`); unchanged visual assets and theme values are retained.

Payment actions use suspending application contracts and ViewModel-owned coroutines. An in-flight action shows progress and rejects repeated submissions; later results update the session without taking over a newly opened screen. Cancellation is not reported as payment failure, and restored state cannot replay a command. Repository snapshots own and protect their lists against external mutation. All four flavors still use the same demo operations.

## Visual system

Online: deep pine (#103F38), mint (#C5F2D8), soft white (#F6F8F7), peach (#FBE8D8), lavender (#ECE8F7), and ink (#173D35). Bundled variable Manrope typography uses explicit weight axes. The wallet is the principal visual anchor; quiet service tiles and unboxed transaction rows keep the rest readable. Vector icons and the launcher mark are drawn locally. The Manrope license is bundled in `app/src/main/assets/licenses`.

Offline: palette tokens and inline accents resolve to their greyscale luminance, including dialogs, controls and the native date picker. Selected and enabled controls retain contrast; typography and layout stay unchanged.

`ui/AxiaApp.kt` contains the four main screens, `ui/PaymentSheet.kt` renders payment forms/reviews, `ui/OfflineSheets.kt` renders connection/outbox/SMS states, `ui/Sheets.kt` contains the other sheets, `ui/components` contains the shared controls and vector icons, and `ui/theme` defines the palette and typography. `AxiaHomePreview` provides an Android Studio Compose preview.
