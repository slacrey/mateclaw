# Home Recommended Entry Design

Date: 2026-06-02
Status: Approved direction

## Context

MateClaw currently lands users in `ChatConsole` or the analytics-style `Dashboard`.
The left sidebar exposes `仪表盘` / `Dashboard` as a core menu item.

The approved visual direction is **方案 A：推荐工作台**. The new home page should be a practical entry point, not a marketing landing page. Its primary job is to help users quickly discover a recommended digital employee, start a conversation, and scan the latest scheduled-task activity.

## Decisions

- Add a new `首页` / `Home` route as the authenticated product entry page.
- Hide the `仪表盘` / `Dashboard` menu item from the left sidebar.
- Keep the existing `/dashboard` route available for direct links and permission compatibility, but do not show it in primary navigation.
- Redirect `/` to `/home` for users whose workspace can view the home page.
- Use the existing warm MateClaw surface language: ivory background, burnt-orange primary action, deep-teal accent, compact dashboard spacing.
- Do not create a landing-page hero. The first screen remains an app workspace.

## Page Structure

### 1. Carousel Banner

The top banner is a wide carousel area with one active banner at a time.

Each banner can define:

- title
- subtitle
- visual accent or background image
- optional `demoVideoUrl`
- optional `clientDownloadUrl`
- optional `browserPluginDownloadUrl`

If `demoVideoUrl` is present, show a visible play button labeled `查看演示效果`.
Clicking it opens an in-page video player modal.
If `demoVideoUrl` is absent, the play button is not rendered.

Clicking the banner itself opens an environment-specific prompt:

- Web environment: show `此功能需下载客户端才可使用`.
- Client environment: show `请确保已安装浏览器插件`.

In the client prompt, reserve a `下载浏览器插件` button. It is disabled or hidden only when no browser-plugin URL is configured.

Environment detection is client-side and conservative:

- Treat as client if Electron-style globals are present, such as `window.electronAPI`, `window.__TAURI__`, or an Electron user agent marker.
- Otherwise treat as web.

### 2. Digital Employee Market

The section title is `数字员工市场`.

It shows enabled digital employees as recommended cards. Cards display:

- name
- icon
- role label, derived from tags or fallback text, capped visually at 10 Chinese characters
- goal, derived from prompt/description or fallback text, capped visually at 14 Chinese characters
- description, capped visually at 30 Chinese characters

Cards are clickable. On hover, the card reveals an affordance: `开始对话`.

Clicking a card routes to chat with:

```text
/chat?agentId=<agent.id>&action=newChat
```

`ChatConsole` already captures `action=newChat`, creates a local conversation, focuses input, and syncs the selected agent from `agentId`.

Only enabled employees should appear on the home page.

### 3. Recent Scheduled Task Runs

The bottom section is `最近定时任务执行`.

It reuses existing dashboard run data from:

```ts
dashboardApi.recentRuns(8)
```

The list shows:

- execution time
- job id/name if available
- status
- trigger type
- duration

The table stays compact and readable. Empty state text: `暂无执行记录`.

## Interaction States

- Banner loading: keep banner shell height stable.
- Banner missing video: no play button.
- Banner click in web: informational modal with client download button if URL exists.
- Banner click in client: informational modal with browser-plugin download button if URL exists.
- Digital employee card hover: subtle lift, primary action reveal, cursor pointer.
- Digital employee click: navigate to a new chat for that employee.
- No employees: show a small empty state and link to `/agents` for admins.
- Scheduled run loading/error: non-blocking; show empty state if unavailable.

## Accessibility

- Banner carousel controls are buttons with labels.
- Video player modal uses `role="dialog"` and `aria-modal="true"`.
- Employee cards are buttons or links, not plain clickable divs.
- Focus states match existing app tokens.
- Video prompt and environment prompt can close by Escape and overlay click.

## Data And Configuration

For the first implementation, keep banner configuration in the home page module or a small adjacent config file. This avoids adding backend schema and API before product needs require operational editing.

Example shape:

```ts
interface HomeBanner {
  id: string
  title: string
  subtitle: string
  demoVideoUrl?: string
  clientDownloadUrl?: string
  browserPluginDownloadUrl?: string
}
```

If later required, this shape can move behind an API without changing the page contract.

## Testing

Add focused UI contract tests for:

- `/home` route exists and `/` redirects to `/home`.
- Sidebar contains `首页` and does not contain the visible dashboard nav item.
- Home page calls enabled-agent listing and recent cron runs.
- Banner video button is conditional on `demoVideoUrl`.
- Employee click routes to `/chat` with `agentId` and `action=newChat`.
- Environment prompt differs between web and client.
