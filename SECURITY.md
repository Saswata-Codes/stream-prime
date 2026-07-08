# Security Policy

## Supported Versions

Security fixes are currently provided for the latest public version of Stream Prime.

## Reporting A Vulnerability

Please do not open a public issue for vulnerabilities involving credentials, stream keys, private RTMP URLs, signing keys, or exploitable app behavior.

Use GitHub Security Advisories if available on the repository, or contact the maintainer through the GitHub profile:

https://github.com/Saswata-Codes

Include:

- A clear description of the issue
- Steps to reproduce
- Affected Android versions/devices if known
- Impact
- Suggested fix if you have one

Do not include real stream keys, production endpoints, passwords, or private signing material in the report.

## Sensitive Data Guidelines

- Keep `local.properties` out of Git.
- Keep signing keys and keystore passwords out of Git.
- Redact stream keys from screenshots, logs, and bug reports.
- Avoid logging full RTMP URLs because they may contain private stream keys.
