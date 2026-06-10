# MateClaw UI Lightfield Theme Implementation Plan

> **For Claude:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task.

**Goal:** Replace the current warm MateClaw UI theme with a full-site cold-blue “lightfield command center” backend theme based on the approved design.

**Architecture:** Keep the existing Vue 3 app structure and route layout. Use the existing CSS token system in `src/assets/main.css` as the main theming layer, then tune the shell, Home, Chat, Dashboard, and chat sidebar where local scoped CSS currently hard-codes warm visual behavior.

**Tech Stack:** Vue 3, Vite, TypeScript, Pinia, Element Plus, scoped CSS, Vitest, Playwright/in-app Browser for visual verification.

---

### Task 1: Add Theme Token Regression Tests

**Files:**
- Create: `mateclaw-ui/src/assets/__tests__/lightfieldThemeTokens.test.ts`
- Modify: none
- Test: `mateclaw-ui/src/assets/__tests__/lightfieldThemeTokens.test.ts`

**Step 1: Write the failing test**

Create `mateclaw-ui/src/assets/__tests__/lightfieldThemeTokens.test.ts`:

```ts
import { describe, expect, it } from 'vitest'
import css from '../main.css?raw'

describe('lightfield theme tokens', () => {
  it('uses the approved cold-blue brand palette instead of the old warm palette', () => {
    expect(css).toContain('--mc-primary: #476CFF')
    expect(css).toContain('--mc-primary-hover: #3455F4')
    expect(css).toContain('--mc-accent: #19BFD1')
    expect(css).toContain('--mc-bg: #eef6ff')
    expect(css).not.toContain('--mc-primary: #d96d46')
    expect(css).not.toContain('--mc-bg: #f6f1ea')
  })

  it('keeps state colors distinct so the interface does not become one-note blue', () => {
    expect(css).toContain('--mc-success: #22C55E')
    expect(css).toContain('--mc-warning: #F59E0B')
    expect(css).toContain('--mc-danger: #EF476F')
    expect(css).toContain('--mc-info: #476CFF')
  })

  it('defines lightfield surface and sidebar tokens used by the layout shell', () => {
    expect(css).toContain('--mc-lightfield-grid')
    expect(css).toContain('--mc-sidebar-bg: rgba(8, 22, 66, 0.88)')
    expect(css).toContain('--mc-sidebar-active: linear-gradient(135deg, #476CFF, #6E8BFF)')
  })
})
```

**Step 2: Run the test to verify it fails**

Run:

```bash
cd mateclaw-ui
pnpm test src/assets/__tests__/lightfieldThemeTokens.test.ts
```

Expected: FAIL because the old warm token values are still present and the new test file references tokens that do not exist.

**Step 3: Commit only the failing test**

```bash
git add mateclaw-ui/src/assets/__tests__/lightfieldThemeTokens.test.ts
git commit -m "test(ui): capture lightfield theme tokens"
```

### Task 2: Replace Global Theme Tokens

**Files:**
- Modify: `mateclaw-ui/src/assets/main.css`
- Test: `mateclaw-ui/src/assets/__tests__/lightfieldThemeTokens.test.ts`

**Step 1: Update root tokens**

In `:root`, replace warm palette values with the lightfield palette. Keep the existing variable names so the rest of the app inherits the theme with minimal component churn.

Use this token direction:

```css
:root {
  --mc-primary: #476CFF;
  --mc-primary-light: #9DB5FF;
  --mc-primary-hover: #3455F4;
  --mc-primary-bg: rgba(71, 108, 255, 0.12);
  --mc-accent: #19BFD1;
  --mc-accent-soft: rgba(25, 191, 209, 0.12);

  --mc-bg: #eef6ff;
  --mc-bg-elevated: rgba(255, 255, 255, 0.92);
  --mc-bg-sunken: #dfeaff;
  --mc-bg-muted: rgba(231, 240, 255, 0.78);
  --mc-surface-strong: rgba(255, 255, 255, 0.96);
  --mc-surface-overlay: rgba(255, 255, 255, 0.68);
  --mc-panel-top: rgba(255, 255, 255, 0.78);
  --mc-panel-bottom: rgba(242, 247, 255, 0.88);
  --mc-panel-raised: rgba(255, 255, 255, 0.76);

  --mc-border: rgba(120, 151, 226, 0.42);
  --mc-border-light: rgba(162, 187, 242, 0.34);
  --mc-border-strong: rgba(71, 108, 255, 0.48);

  --mc-text-primary: #122047;
  --mc-text-secondary: #415276;
  --mc-text-tertiary: #7383a8;
  --mc-text-inverse: #ffffff;

  --mc-shadow-soft: 0 14px 34px rgba(45, 83, 180, 0.12);
  --mc-shadow-medium: 0 22px 58px rgba(38, 70, 165, 0.16);
  --mc-shadow-strong: 0 32px 90px rgba(20, 44, 130, 0.22);
  --mc-glow: radial-gradient(circle at top, rgba(111, 145, 255, 0.22), transparent 58%);
  --mc-lightfield-grid: rgba(255, 255, 255, 0.24);

  --mc-sidebar-bg: rgba(8, 22, 66, 0.88);
  --mc-sidebar-border: rgba(166, 194, 255, 0.28);
  --mc-sidebar-hover: rgba(255, 255, 255, 0.10);
  --mc-sidebar-active: linear-gradient(135deg, #476CFF, #6E8BFF);
  --mc-sidebar-text: rgba(226, 236, 255, 0.76);
  --mc-sidebar-text-active: #ffffff;
  --mc-sidebar-group-title: rgba(192, 210, 255, 0.56);
  --mc-sidebar-logo-name: #ffffff;
  --mc-sidebar-footer-bg: rgba(5, 17, 54, 0.46);
  --mc-sidebar-floating-bg: rgba(13, 32, 86, 0.92);

  --mc-chat-bg: rgba(244, 248, 255, 0.92);
  --mc-chat-header-bg: rgba(255, 255, 255, 0.78);
  --mc-assistant-bubble-bg: rgba(255, 255, 255, 0.86);
  --mc-assistant-bubble-border: rgba(155, 181, 255, 0.32);
  --mc-assistant-bubble-color: #122047;
  --mc-user-bubble-bg: linear-gradient(135deg, #476CFF, #6E8BFF);
  --mc-user-bubble-color: #ffffff;
  --mc-input-bg: rgba(255, 255, 255, 0.92);
  --mc-input-border: rgba(120, 151, 226, 0.42);
  --mc-input-text: #122047;

  --mc-success: #22C55E;
  --mc-warning: #F59E0B;
  --mc-warning-hover: #D97706;
  --mc-info: #476CFF;
  --mc-danger: #EF476F;
}
```

Also update Element Plus root variables:

```css
--el-color-primary: #476CFF;
--el-font-family: var(--mc-font-body);
```

**Step 2: Update global body background**

Replace warm radial backgrounds with a lightfield composition:

```css
body {
  background-color: var(--mc-bg);
  background-image:
    radial-gradient(circle at 86% 12%, rgba(255, 255, 255, 0.86), transparent 22%),
    radial-gradient(circle at 18% 8%, rgba(47, 82, 202, 0.42), transparent 28%),
    linear-gradient(135deg, #071442 0%, #1f4db8 36%, #d9f1ff 100%);
}

body::before {
  background-image:
    linear-gradient(var(--mc-lightfield-grid) 1px, transparent 1px),
    linear-gradient(90deg, var(--mc-lightfield-grid) 1px, transparent 1px);
  background-size: 88px 88px;
  opacity: 0.22;
  mix-blend-mode: screen;
}
```

**Step 3: Update shared shell and card surfaces**

Tune `.mc-page-frame`, `.mc-page-frame::before`, `.mc-surface-card`, code blocks, markdown tables, and workflow canvas token bridge so they use the new tokens. Keep high-density content readable by making cards at least 0.78 opacity over the grid.

**Step 4: Run the token test**

```bash
cd mateclaw-ui
pnpm test src/assets/__tests__/lightfieldThemeTokens.test.ts
```

Expected: PASS.

**Step 5: Commit**

```bash
git add mateclaw-ui/src/assets/main.css
git commit -m "style(ui): replace warm theme tokens with lightfield palette"
```

### Task 3: Theme the Main Layout Shell and Sidebar

**Files:**
- Modify: `mateclaw-ui/src/views/layout/MainLayout.vue`
- Test: `mateclaw-ui/src/views/layout/__tests__/appearancePreferences.test.ts`

**Step 1: Update scoped layout CSS**

In `MainLayout.vue`, update:

- `.app-layout::before` to use the global lightfield glow.
- `.sidebar` to use `var(--mc-sidebar-bg)` with `backdrop-filter`.
- `.logo-icon` to use blue/cyan glow instead of warm orange.
- `.nav-item.active` so the background accepts the gradient token:

```css
.nav-item.active {
  background: var(--mc-sidebar-active);
  color: var(--mc-sidebar-text-active);
  font-weight: 700;
  box-shadow:
    0 10px 24px rgba(71, 108, 255, 0.26),
    inset 0 0 0 1px rgba(255, 255, 255, 0.22);
}
```

Update sidebar footer controls so they remain legible on the dark sidebar:

```css
.sidebar-utility-card,
.user-info,
.health-indicator,
.language-btn {
  background: rgba(255, 255, 255, 0.08);
  border-color: rgba(197, 215, 255, 0.18);
  color: var(--mc-sidebar-text);
}
```

**Step 2: Preserve appearance preference behavior**

Do not reintroduce `theme-toggle-row`, `themeStore.setMode`, or `t('nav.themeLabel')` into `MainLayout.vue`.

**Step 3: Run the layout preference test**

```bash
cd mateclaw-ui
pnpm test src/views/layout/__tests__/appearancePreferences.test.ts
```

Expected: PASS.

**Step 4: Commit**

```bash
git add mateclaw-ui/src/views/layout/MainLayout.vue
git commit -m "style(ui): apply lightfield sidebar shell"
```

### Task 4: Theme the Home Page as the Visual Sample Page

**Files:**
- Modify: `mateclaw-ui/src/views/Home/index.vue`
- Test: `mateclaw-ui/src/views/Home/__tests__/homePageContract.test.ts`
- Test: `mateclaw-ui/src/views/Home/__tests__/homeNavigation.test.ts`

**Step 1: Update the banner**

Change `.home-banner` to read like a backend-ready version of the reference image:

```css
.home-banner {
  border-color: rgba(178, 202, 255, 0.38);
  background:
    radial-gradient(circle at 82% 18%, rgba(255, 255, 255, 0.70), transparent 25%),
    linear-gradient(135deg, rgba(18, 44, 132, 0.88), rgba(78, 121, 255, 0.62) 48%, rgba(224, 245, 255, 0.72)),
    linear-gradient(180deg, var(--mc-panel-top), var(--mc-panel-bottom));
  color: #ffffff;
}
```

Add a grid overlay through `.home-banner::before` and keep it subtle:

```css
.home-banner::before {
  content: '';
  position: absolute;
  inset: 0;
  background-image:
    linear-gradient(rgba(255, 255, 255, 0.16) 1px, transparent 1px),
    linear-gradient(90deg, rgba(255, 255, 255, 0.16) 1px, transparent 1px);
  background-size: 72px 72px;
  opacity: 0.34;
  pointer-events: none;
}
```

**Step 2: Update banner text and visual nodes**

Use white text for the banner title and soft blue-white for the subtitle. Replace warm nodes with blue/cyan nodes.

**Step 3: Update employee cards and recent runs**

Employee cards should use glass surfaces but keep text readable:

```css
.employee-card {
  background: rgba(255, 255, 255, 0.82);
  border-color: rgba(155, 181, 255, 0.32);
}

.employee-card:hover {
  border-color: var(--mc-primary-light);
  box-shadow: 0 18px 42px rgba(71, 108, 255, 0.18);
}
```

Keep `.home-runs` mostly opaque so table rows stay readable.

**Step 4: Run Home tests**

```bash
cd mateclaw-ui
pnpm test src/views/Home/__tests__/homePageContract.test.ts src/views/Home/__tests__/homeNavigation.test.ts
```

Expected: PASS.

**Step 5: Commit**

```bash
git add mateclaw-ui/src/views/Home/index.vue
git commit -m "style(ui): restyle home page for lightfield theme"
```

### Task 5: Theme the Chat Console

**Files:**
- Modify: `mateclaw-ui/src/views/ChatConsole.vue`
- Modify: `mateclaw-ui/src/components/chat/ConversationSidebar.vue`
- Modify as needed: `mateclaw-ui/src/components/chat/MessageBubble.vue`
- Modify as needed: `mateclaw-ui/src/components/chat/ChatInput.vue`
- Test: existing chat tests if present; otherwise run targeted build after this task.

**Step 1: Update ChatConsole frame**

Tune `.chat-area`, `.chat-header`, `.agent-badge`, `.model-prompt`, `.btn-primary`, and `.drop-overlay` to use cold-blue surfaces.

Important: `.btn-primary` must support `--mc-user-bubble-bg` being a gradient. Avoid assigning it to text color or border color.

**Step 2: Update ConversationSidebar**

Make the conversation panel a lower-contrast deep blue glass panel:

```css
.conversation-panel {
  background:
    linear-gradient(180deg, rgba(8, 22, 66, 0.84), rgba(15, 39, 95, 0.72));
  border-right: 1px solid rgba(166, 194, 255, 0.22);
}
```

Then tune panel title, filter select, conversation hover, active state, running badge, and context buttons to stay readable on the dark panel.

**Step 3: Check message bubble compatibility**

Inspect `MessageBubble.vue` for places where `--mc-user-bubble-bg` is treated as a color. If it is used only as `background`, no change is needed. If a gradient token breaks any `color-mix()` call, introduce a separate fallback token such as `--mc-primary-solid: #476CFF` in `main.css` and use it there.

**Step 4: Check ChatInput compatibility**

Ensure primary send button, thinking toggle, attachment chips, approval states, and queued message controls still have sufficient contrast.

**Step 5: Run available tests**

```bash
cd mateclaw-ui
pnpm test
```

Expected: PASS, unless unrelated pre-existing tests fail. Record any unrelated failures before continuing.

**Step 6: Commit**

```bash
git add mateclaw-ui/src/views/ChatConsole.vue mateclaw-ui/src/components/chat/ConversationSidebar.vue mateclaw-ui/src/components/chat/MessageBubble.vue mateclaw-ui/src/components/chat/ChatInput.vue
git commit -m "style(ui): align chat console with lightfield theme"
```

### Task 6: Theme Dashboard and Chart Colors

**Files:**
- Modify: `mateclaw-ui/src/views/Dashboard.vue`
- Test: no dedicated Dashboard test currently required; verify through build and browser.

**Step 1: Update stats, model, comparison, and table cards**

Use the new card surfaces and state colors. Avoid lowering opacity for secondary cards so text remains readable.

**Step 2: Update chart palette**

In the chart setup code, read the new CSS variables and use distinct colors:

```ts
const primaryColor = style.getPropertyValue('--mc-primary').trim() || '#476CFF'
const accentColor = style.getPropertyValue('--mc-accent').trim() || '#19BFD1'
const successColor = style.getPropertyValue('--mc-success').trim() || '#22C55E'
const warningColor = style.getPropertyValue('--mc-warning').trim() || '#F59E0B'
```

Use these for line series and status markers so the dashboard does not collapse into a single blue palette.

**Step 3: Commit**

```bash
git add mateclaw-ui/src/views/Dashboard.vue
git commit -m "style(ui): update dashboard lightfield visuals"
```

### Task 7: Run Build and Browser Verification

**Files:**
- Modify only if verification finds defects in files touched by earlier tasks.

**Step 1: Run typecheck/build**

```bash
cd mateclaw-ui
pnpm build
```

Expected: PASS. If it fails, classify the failure:

- Theme regression introduced by this work: fix before continuing.
- Existing repo issue, missing dependency, or known script issue: document exact failure.

**Step 2: Start the frontend dev server**

```bash
cd mateclaw-ui
pnpm dev -- --host 127.0.0.1
```

Expected: Vite dev server starts, usually at `http://127.0.0.1:5173`.

**Step 3: Browser-check key pages**

Use the Browser plugin to inspect:

- `http://127.0.0.1:5173/home`
- `http://127.0.0.1:5173/chat`
- `http://127.0.0.1:5173/dashboard`
- `http://127.0.0.1:5173/settings/models`
- `http://127.0.0.1:5173/security/approvals`

Check desktop width and a mobile width around 390px.

Expected:

- No warm brown/terracotta theme remnants in the main shell.
- Sidebar active states are readable.
- Tables and forms have strong enough contrast.
- Chat input and message bubbles do not overlap.
- Homepage banner resembles the approved lightfield direction without becoming a marketing-only landing page.

**Step 4: Final cleanup commit**

If verification fixes were needed:

```bash
git add <fixed-files>
git commit -m "fix(ui): polish lightfield theme verification issues"
```

If no fixes were needed, do not create an empty commit.

### Task 8: Final Review

**Files:**
- Read: `git diff main...HEAD -- mateclaw-ui docs/plans`

**Step 1: Review for scope**

Confirm the implementation did not:

- Reintroduce theme controls removed by `appearancePreferences.test.ts`.
- Modify backend code.
- Change routes, capabilities, or API behavior.
- Convert the app into a marketing page.

**Step 2: Run final verification commands**

```bash
cd mateclaw-ui
pnpm test src/assets/__tests__/lightfieldThemeTokens.test.ts src/views/layout/__tests__/appearancePreferences.test.ts
pnpm build
```

Expected: PASS or documented pre-existing failure.

**Step 3: Report outcome**

Summarize:

- Files changed.
- Tests run.
- Browser pages checked.
- Any known residual risk.
