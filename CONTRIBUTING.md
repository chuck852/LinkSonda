# Contributing

For ideas, start with a feature request describing the use case and expected behavior. Maintainers review proposals before they become part of the roadmap. For a bug, include the app version or commit, Android version, device, and a minimal way to reproduce it. Use [private reporting](SECURITY.md) for a security issue.

## Branch and pull request

1. Fetch `origin/main` and create a topic branch from it. Use a name such as `fix/timeout-handling` or `docs/build-guide`.
2. Keep the change focused, use descriptive commits, and add or update tests relevant to the change. Use controlled or explicitly authorized targets for tests that send network traffic.
3. Run `./gradlew testDebugUnitTest lintDebug assembleDebug`. Update the docs when user-facing behavior changes.
4. Open a pull request against `main` and include the verification results. Before merging, fetch `origin/main` again, check the base and review the current CI results. If updates are needed, merge `origin/main` into the topic branch and push normally.

Participate under the [code of conduct](CODE_OF_CONDUCT.md). Submit only changes you have permission to contribute under [Apache-2.0](LICENSE), with all required third-party notices preserved.
