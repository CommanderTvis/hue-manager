# Desktop packaging

- Scope startup optimizations by platform: NIK packaging is requested for macOS only. Keep Linux on its existing JVM/Flatpak path unless a Linux migration is explicitly requested.

- A missing native-library dependency is not proof of Native Image incompatibility. Inspect the actual undefined symbols and transitive dependencies, test library loading, and check which libraries the process loads before deciding that framework changes are required.
- Native Image startup tracing covers only exercised paths. Before handing a desktop build over for interactive testing, verify API serialization and text input in the native executable; opening a window does not validate Ktor's reflective serializer lookup.

## Git delivery

- For this repository, deliver requested commits and pushes to master unless the user requests another branch. A temporary worktree branch is an implementation detail, not the delivery target; remove it after merging.
