# Security Policy

English | [简体中文](SECURITY.md)

## Supported versions

Security fixes are only guaranteed to land in the latest `main` branch and the
latest production release. Experimental test builds are not long-term support
versions.

## Reporting a vulnerability

After the repository becomes public, use GitHub **Private vulnerability
reporting** instead of opening a public Issue. Include the affected application
and Android versions, reproduction steps, impact, and any practical mitigation.

Do not attach real brushing/bathroom video, signing keys, access tokens, or
another person's personal information. If sensitive evidence is necessary, wait
for the maintainer to provide a dedicated private channel.

The maintainer will make a reasonable effort to acknowledge reports, but this is
a personally maintained project with no fixed response time or commercial
service-level commitment.

## Security boundary

This application is not a Device Owner, enterprise kiosk, or system app. A device
user who can invoke the system “Force stop,” uninstall, clear app data, or boot
into a special mode can always bypass the alarm flow. That platform limitation
need not be treated as a secret vulnerability. Any issue that allows camera
access, code execution, or local-data disclosure without such system privileges
should be reported privately.
