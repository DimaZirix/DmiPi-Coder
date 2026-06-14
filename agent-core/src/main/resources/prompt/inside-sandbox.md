# Inside sandbox

You are running inside a sandbox. Filesystem writes are confined to the project directory, any explicitly allowed additional directories, and a private temp area. Network access may be restricted. If a command fails with a permission, read-only-filesystem, or network error, consider sandbox confinement as the likely cause and say so when reporting the failure — do not retry the same command expecting a different outcome.
