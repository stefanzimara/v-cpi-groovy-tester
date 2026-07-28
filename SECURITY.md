# Security Policy

## What this tool is

CPI Groovy Tester executes whatever Groovy code you give it, with the full
permissions of the user running it, and reads/writes files inside its project
directory. That is its entire purpose — it is a **local developer tool**, the
same trust model as a REPL, `groovy` on the command line, or your IDE's run
button. It is **not** a hardened multi-user service, and it must never be
exposed to users you do not trust or to the open internet.

If you are looking for a way to let untrusted third parties run scripts
through a hosted instance, this project's architecture does not support that
safely — it would need per-session sandboxing (container isolation, resource
limits, network egress control), which is out of scope here.

## Built-in mitigations

| Risk | Mitigation |
|---|---|
| Network exposure | Server binds to `127.0.0.1` by default. `--bind` to a non-loopback address requires an access token (auto-generated unless `--token` is given). |
| Path traversal | File load/save (`/api/load`, `/api/save`) resolve and normalize against the project root and refuse anything that escapes it. |
| "localhost CSRF" (a hostile page open in another tab silently triggering code execution on this tool) | `POST /api/run` and `POST /api/save` require a custom request header (`X-Tester-Csrf`) that neither an HTML `<form>` nor a cross-origin `fetch`/`XHR` can set without a CORS preflight this server does not answer. This protection is active even in the default loopback-only mode without a token. |
| Memory exhaustion via oversized requests | Request bodies are capped (25 MB) before parsing. |

## What is on you

- **Never** put this behind a public IP or expose the port via router
  port-forwarding. For remote access, use an SSH tunnel or a VPN.
- There is **no built-in TLS**. All traffic, including the access token, is
  plaintext. Treat it accordingly — fine on `localhost` or a trusted LAN,
  not fine over an untrusted network.
- `--no-token` combined with a network bind (`--bind lan`/`--bind <ip>`)
  removes all access control for anyone who can reach the port. Only use
  this combination on networks you fully control.
- Only run scripts from sources you trust. The tool does not — and cannot —
  sandbox the Groovy it executes.
- The bundled example credentials in `testdata/config.json` are placeholders.
  Do not commit real credentials into a config file that stays in your git
  history; keep real ones in a file matched by `.gitignore` instead (e.g.
  `*.local.json`).

## Reporting a vulnerability

If you find a security issue beyond what's described above (for example, a
path-traversal bypass or a way to hit `/api/run`/`/api/save` without the CSRF
header), please open a private report:

- Preferred: GitHub's "Report a vulnerability" under this repository's
  Security tab (uses GitHub Security Advisories, keeps the report private
  until a fix is out).
- Alternative: open an issue that says only "possible security issue, please
  contact me" without details, and a maintainer will follow up for a private
  channel.

Please do not open a public issue with exploit details before a fix is
available. There is no bug-bounty program; this is a community-maintained
open-source tool.
