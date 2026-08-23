# JAVIS — AI Assistant for Android

A real, buildable Android Studio project: a personal AI assistant with voice
and text input, backed by a configurable LLMPI-compatible AI endpoint, with
a secure command system and full offline fallback.

## What's included

- Jetpack Compose UI (dark mode, JARVIS-styled)
- Voice input (`SpeechRecognizer`) and voice output (`TextToSpeech`)
- `AIBackend` abstraction with two implementations:
  - `LLMPIBackend` — talks to your real LLMPI endpoint over HTTPS
  - `MockBackend` — fully local, so the app runs with zero configuration
- `OfflineEngine` — local-only responder used automatically when there's no network
- A closed-set command system (`JavisCommand` + `CommandRouter` + `CommandValidator`)
  so the AI can never trigger arbitrary code — only a fixed allowlist of actions
- A `ToolRegistry` with real, working tools: open app, web search, time,
  calculator, notes (local storage), notifications, settings shortcuts
- Confirmation dialog for any action that needs explicit user approval
- Conversation history (in-memory, clearable)
- Unit tests for the router, validator, and mock backend

## Requirements

- Android Studio (Koala or newer recommended)
- JDK 17
- An Android device or emulator running API 26+ (Android 8.0+)

## Setup

1. Open this project folder in Android Studio (`File → Open`).
2. Copy `local.properties.example` to `local.properties` in the project root.
3. Edit `local.properties`:
   ```
   sdk.dir=/path/to/your/Android/sdk   # Android Studio usually fills this in for you
   LLMPI_BASE_URL=https://your-llmpi-endpoint.example.com
   LLMPI_API_KEY=your_api_key_here
   ```
   **If you don't have an LLMPI endpoint yet**, leave these as the placeholder
   values (or skip this step). The app automatically falls back to
   `MockBackend`, a small local responder, so you can build, run, and test
   the full app — UI, voice, tools, confirmation flow — with zero backend.
4. Let Gradle sync, then Run on a device or emulator.

## LLMPI backend contract

`LLMPIBackend` sends:
```
POST {LLMPI_BASE_URL}/v1/assistant/process
Authorization: Bearer {LLMPI_API_KEY}
Content-Type: application/json

{
  "message": "open whatsapp",
  "conversationId": "…",
  "deviceContext": { "networkAvailable": true, "currentTimeIso": "…" }
}
```

And expects back:
```json
{
  "type": "action",
  "action": "open_app",
  "target": "WhatsApp",
  "message": "Opening WhatsApp.",
  "requiresConfirmation": false
}
```
or for plain conversation:
```json
{ "type": "response", "message": "Hello! How can I help you?" }
```

If your real LLMPI provider uses a different endpoint path or payload shape,
edit `LLMPIBackend.kt` only — nothing else in the app depends on the wire
format, since everything talks to the `AIBackend` interface.

**This exact endpoint path (`/v1/assistant/process`) is a placeholder
convention, not a documented LLMPI standard** — adjust it to match whatever
your actual provider specifies.

## Security model

- The AI backend can only ever name one of a fixed set of actions
  (`CommandValidator.ALLOWED_ACTIONS`) — anything else is rejected before
  it reaches a tool.
- No arbitrary shell or code execution exists anywhere in the app.
- Free-text targets are checked for shell metacharacters before use.
- `show_notification` always requires user confirmation, regardless of
  what the AI response says.
- Permissions (microphone, notifications) are requested only when needed,
  with a rationale string, and denial never crashes the app — it just
  falls back to typing / no notifications.

## Adding a new capability

1. Add the action name to `CommandValidator.ALLOWED_ACTIONS`.
2. Add a case in `CommandRouter.route()` mapping it to a new `JavisCommand`.
3. Implement a new `JavisTool` in `tools/` and register it in `ToolRegistry`.
4. Update your LLMPI system prompt so the backend knows the new action exists.

## Tests

Run `./gradlew test` for unit tests covering the command router, the
validator's allowlist/rejection behavior, and the mock backend's pattern
matching.

## Known limitations / next steps

- `AccessibilityService` is intentionally not implemented. If a future
  capability genuinely needs it, it must be a separate, clearly-explained
  opt-in the user enables manually in Android Settings — never silently
  auto-enabled.
- Conversation history is in-memory only (cleared on process death). Swap
  in a small Room database if you want persistence across app restarts.
- `NotesStore` keeps a single note for simplicity; extend to a list +
  Room database for multiple notes.
- App-name matching in `OpenAppTool` does simple case-insensitive
  substring matching against installed app labels — good enough for voice
  commands, but you may want fuzzy matching for typos.
