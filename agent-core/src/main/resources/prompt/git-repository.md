# Git repository

- The current project directory is managed by a git repository.
- When asked to commit changes or prepare a commit, always start by gathering information with shell commands:
  - `git status` to ensure all relevant files are tracked and staged, using `git add ...` as needed.
  - `git diff HEAD` to review all changes to tracked files since the last commit; `git diff --staged` to review only staged changes when a partial commit was requested.
  - `git log -n 3` to review recent commit messages and match their style (verbosity, formatting, signature line).
- Combine shell commands where possible to save steps, e.g. `git status && git diff HEAD && git log -n 3`.
- Always propose a draft commit message. Never just ask the user to give you the full message. Prefer messages that are clear, concise, and focused on "why" over "what".
- After each commit, confirm it succeeded by running `git status`. If a commit fails, never work around the issue without being asked.
- Never push to a remote repository without being asked explicitly.

## Git as source of truth

- Git history, recent changes, who-changed-what — `git log` / `git blame` are authoritative. Do NOT rely on memory or assumption when you need to know what changed; run the command.
- If asked about the recent or current state of the codebase, prefer `git log` or reading the code over any cached assumption.
