# Security Policy

## Supported version

Security fixes currently target the latest published beta release.

| Version | Supported |
| --- | --- |
| 0.5.2-beta | Yes |
| Older builds | No |

## Reporting a vulnerability

Use GitHub's **Report a vulnerability** private reporting option on the Security
page when it is available. If private reporting is unavailable, open an issue
titled `Security contact request` with no sensitive technical details so a private
contact channel can be arranged.

Do not publish or attach:

- passwords, PINs or one-time codes;
- private typed text or clipboard contents;
- personal OCR images or contact data;
- signing keys, keystores, passwords or API tokens;
- an exploit that exposes another user's data.

Include the affected version, Android version, device model, reproduction scope
and expected impact. Allow reasonable time for investigation and release of a fix
before public disclosure.

## Signing key safety

The release keystore and its passwords must never be committed to the repository,
attached to an issue or included in a build artifact. GitHub Actions signing values
must remain encrypted repository secrets.
