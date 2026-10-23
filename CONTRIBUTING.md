# Contributing

Thanks for helping out! Keep it simple:

1. Fork the repo, create a branch, and keep each PR focused on one change.
2. Run `./gradlew testDebugUnitTest` before opening a PR. If you change the face, player or settings UI, also run `./gradlew recordPaparazziDebug` and commit the updated snapshots.
3. Add user-facing text to **both** `res/values/strings.xml` (English) and `res/values-in/strings.xml` (Indonesian).
4. **Never commit secrets.** API keys, tokens, `sessionid` cookies, `local.properties`, keystores and `.env` files are git-ignored for a reason. Live tests read keys from environment variables only. Don't log keys or full request bodies.
5. New network services should have a timeout, fail gracefully with a fallback, and be documented in `docs/services.md`.
