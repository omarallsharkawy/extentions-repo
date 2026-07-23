# Product

## Register

brand

## Users

Android users of Aniyomi-compatible applications, especially Arabic- and English-speaking users, who want a dependable way to add this extension repository, discover an extension, and install its current APK. Maintainers also need a predictable repository layout that can be verified and updated without touching unrelated extensions.

## Product Purpose

Provide a trustworthy, low-friction entry point to Omar's Aniyomi extension repository. The product should let a user open the repository in a compatible app with one tap, copy the repository URL when deep linking is unavailable, browse and search the published extensions, and download APKs manually as a fallback. It must clearly distinguish opening an app from granting trust or installation approval; those confirmations remain visible application-controlled actions.

## Brand Personality

Direct, dependable, technically disciplined, and welcoming. The interface should feel maintained and transparent rather than promotional. It may be concise and confident, but never imply that permissions, trust prompts, or Android security checks can be bypassed.

## Anti-references

- Flashy marketing pages, generic SaaS dashboards, glass effects, decorative gradients, and animation that competes with installation.
- Ambiguous buttons that hide which application or repository URL will open.
- Claims of silent access, automatic trust, or permission bypass.
- Dense walls of maintainer detail before the primary install action.
- Multiple conflicting copies of the same APK in public repository paths.

## Design Principles

- Make the correct one-tap app action the unmistakable primary path.
- Prefer truth over magic: explain the confirmation that follows and never promise silent access.
- Keep repository health visible through current extension count, version data, and a clear update state.
- Always preserve a manual fallback through a copyable repository URL and direct APK downloads.
- Stay fast and resilient with a static, dependency-free page and a single canonical asset layout.

## Accessibility & Inclusion

Meet WCAG AA contrast, visible keyboard focus, semantic landmarks, reduced-motion preferences, and touch targets of at least 44 CSS pixels. Status changes such as copying or loading must be announced to assistive technology. Core instructions should be understandable in both English and Arabic, work correctly in right-to-left text, and never rely on color alone. Search, filtering, installation links, and downloads must remain usable without animation or a pointing device.
