# Contact Support Menu Implementation Plan

> **For Claude:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task.

**Goal:** Add a left-sidebar customer-service entry that opens a business QR modal for every logged-in user.

**Architecture:** Extend `MainLayout.vue` so nav items can be either router links or local actions. The new contact-support item is ungated and opens a modal that reuses the existing `/business-qr.svg` static asset. Locale files provide the visible copy.

**Tech Stack:** Vue 3 SFC, Vue Router, vue-i18n, Vitest, existing MateClaw CSS tokens.

---

### Task 1: Write the Failing UI Contract Test

**Files:**
- Create: `mateclaw-ui/src/views/layout/__tests__/contactSupportLayout.test.ts`
- Read: `mateclaw-ui/src/views/layout/MainLayout.vue`
- Read: `mateclaw-ui/src/i18n/locales/zh-CN.ts`
- Read: `mateclaw-ui/src/i18n/locales/en-US.ts`

**Step 1: Write the failing test**

```ts
import { describe, expect, it } from 'vitest'
import { readFileSync } from 'node:fs'
import { resolve } from 'node:path'

const root = resolve(__dirname, '../../../..')
const layout = readFileSync(resolve(root, 'views/layout/MainLayout.vue'), 'utf8')
const zh = readFileSync(resolve(root, 'i18n/locales/zh-CN.ts'), 'utf8')
const en = readFileSync(resolve(root, 'i18n/locales/en-US.ts'), 'utf8')

describe('contact support sidebar entry', () => {
  it('exposes a left-nav action that opens the business QR modal', () => {
    expect(layout).toContain("action: 'contactSupport'")
    expect(layout).toContain("t('nav.contactSupport')")
    expect(layout).toContain('showContactSupport')
    expect(layout).toContain('/business-qr.svg')
  })

  it('defines localized contact support copy', () => {
    expect(zh).toContain("contactSupport: '联系客服'")
    expect(zh).toContain('扫码联系客服')
    expect(en).toContain("contactSupport: 'Contact Support'")
    expect(en).toContain('Scan the QR code')
  })
})
```

**Step 2: Run test to verify it fails**

Run: `cd mateclaw-ui && npx vitest run src/views/layout/__tests__/contactSupportLayout.test.ts`

Expected: FAIL because `action: 'contactSupport'` and modal copy do not exist yet.

### Task 2: Add Contact-Support Nav Behavior

**Files:**
- Modify: `mateclaw-ui/src/views/layout/MainLayout.vue`

**Step 1: Implement minimal nav action support**

- Extend `NavItem` with optional `action?: 'contactSupport'`.
- Make `path` optional.
- Render action items as `<button type="button" class="nav-item nav-item--button">`.
- Keep route items rendered as `<router-link>`.
- Add `openContactSupport()` to set `showContactSupport.value = true` and close the mobile sidebar.
- Add `showContactSupport = ref(false)`.
- Add the new item to the `connect` group with no capability gate.

**Step 2: Run test to verify progress**

Run: `cd mateclaw-ui && npx vitest run src/views/layout/__tests__/contactSupportLayout.test.ts`

Expected: still FAIL until locale copy and modal are added.

### Task 3: Add Modal and Locale Copy

**Files:**
- Modify: `mateclaw-ui/src/views/layout/MainLayout.vue`
- Modify: `mateclaw-ui/src/i18n/locales/zh-CN.ts`
- Modify: `mateclaw-ui/src/i18n/locales/en-US.ts`

**Step 1: Add modal markup**

- Add a dismissible overlay controlled by `showContactSupport`.
- Use `role="dialog"` and `aria-modal="true"`.
- Show `/business-qr.svg`.
- Add a close button and Escape handler.

**Step 2: Add CSS**

- Reuse the existing modal surface language and CSS variables.
- Ensure the QR block has a white background so QR contrast remains strong in dark mode.

**Step 3: Add locale strings**

- Add `nav.contactSupport`.
- Add `contactSupport.title`, `desc`, `hint`, `qrAlt`, and `close`.

**Step 4: Run test to verify it passes**

Run: `cd mateclaw-ui && npx vitest run src/views/layout/__tests__/contactSupportLayout.test.ts`

Expected: PASS.

### Task 4: Verify Build Health

**Files:**
- Verify: `mateclaw-ui/src/views/layout/MainLayout.vue`

**Step 1: Run type/build verification**

Run: `cd mateclaw-ui && npm run build`

Expected: PASS.

**Step 2: Inspect final diff**

Run: `git diff -- mateclaw-ui/src/views/layout/MainLayout.vue mateclaw-ui/src/i18n/locales/zh-CN.ts mateclaw-ui/src/i18n/locales/en-US.ts mateclaw-ui/src/views/layout/__tests__/contactSupportLayout.test.ts docs/plans/2026-05-31-contact-support-design.md docs/plans/2026-05-31-contact-support-menu-implementation.md`

Expected: Diff only contains contact-support docs, test, layout, and locale changes.
