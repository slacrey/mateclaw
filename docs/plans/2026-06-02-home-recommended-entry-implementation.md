# Home Recommended Entry Implementation Plan

> **For Claude:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task.

**Goal:** Replace the visible dashboard entry with a new Home page that presents a carousel banner, recommended digital employee cards, and recent scheduled-task runs.

**Architecture:** Add a `Home.vue` route under the existing authenticated `MainLayout`. Keep `/dashboard` for direct links, but remove it from the sidebar. Home uses existing `agentApi.list({ enabled: true })`, `dashboardApi.recentRuns()`, router handoff to `ChatConsole`, and local banner configuration for the first release.

**Tech Stack:** Vue 3 SFC, Vue Router, vue-i18n, Pinia-free local state, Element Plus icons, existing MateClaw CSS tokens, Vitest contract tests.

---

### Task 1: Add Failing Route And Sidebar Contract Tests

**Files:**
- Create: `mateclaw-ui/src/views/Home/__tests__/homeNavigation.test.ts`
- Read: `mateclaw-ui/src/router/index.ts`
- Read: `mateclaw-ui/src/views/layout/MainLayout.vue`
- Read: `mateclaw-ui/src/i18n/locales/zh-CN.ts`
- Read: `mateclaw-ui/src/i18n/locales/en-US.ts`

**Step 1: Write the failing test**

```ts
import { describe, expect, it } from 'vitest'
import { readFileSync } from 'node:fs'
import { resolve } from 'node:path'

const root = resolve(__dirname, '../../..')
const router = readFileSync(resolve(root, 'router/index.ts'), 'utf8')
const layout = readFileSync(resolve(root, 'views/layout/MainLayout.vue'), 'utf8')
const zh = readFileSync(resolve(root, 'i18n/locales/zh-CN.ts'), 'utf8')
const en = readFileSync(resolve(root, 'i18n/locales/en-US.ts'), 'utf8')

describe('home navigation', () => {
  it('registers home as the authenticated default route', () => {
    expect(router).toContain("redirect: '/home'")
    expect(router).toContain("path: 'home'")
    expect(router).toContain("name: 'Home'")
    expect(router).toContain("component: () => import('@/views/Home/index.vue')")
  })

  it('shows Home in the sidebar and hides Dashboard from the visible menu', () => {
    expect(layout).toContain("path: '/home'")
    expect(layout).toContain("t('nav.home'")
    expect(layout).not.toContain("path: '/dashboard',\n        label: t('nav.dashboard'")
  })

  it('defines localized home labels', () => {
    expect(zh).toContain("home: '首页'")
    expect(en).toContain("home: 'Home'")
  })
})
```

**Step 2: Run test to verify it fails**

Run: `cd mateclaw-ui && npx vitest run src/views/Home/__tests__/homeNavigation.test.ts`

Expected: FAIL because the home route, nav item, and locale strings do not exist yet.

### Task 2: Add Failing Home Page Behavior Contract Tests

**Files:**
- Create: `mateclaw-ui/src/views/Home/__tests__/homePageContract.test.ts`
- Create later: `mateclaw-ui/src/views/Home/index.vue`

**Step 1: Write the failing test**

```ts
import { describe, expect, it } from 'vitest'
import { readFileSync } from 'node:fs'
import { resolve } from 'node:path'

const homePath = resolve(__dirname, '../index.vue')
const home = readFileSync(homePath, 'utf8')

describe('home page contract', () => {
  it('loads recommended employees and recent scheduled runs from existing APIs', () => {
    expect(home).toContain("agentApi.list({ enabled: true })")
    expect(home).toContain('dashboardApi.recentRuns(8)')
  })

  it('renders conditional demo video and environment prompts', () => {
    expect(home).toContain('demoVideoUrl')
    expect(home).toContain('showVideoModal')
    expect(home).toContain('isClientEnvironment')
    expect(home).toContain('browserPluginDownloadUrl')
    expect(home).toContain('clientDownloadUrl')
  })

  it('starts a new chat with the selected employee', () => {
    expect(home).toContain("path: '/chat'")
    expect(home).toContain("action: 'newChat'")
    expect(home).toContain('agentId')
  })
})
```

**Step 2: Run test to verify it fails**

Run: `cd mateclaw-ui && npx vitest run src/views/Home/__tests__/homePageContract.test.ts`

Expected: FAIL because `Home/index.vue` does not exist.

### Task 3: Add Route, Sidebar Entry, And Locale Labels

**Files:**
- Modify: `mateclaw-ui/src/router/index.ts`
- Modify: `mateclaw-ui/src/views/layout/MainLayout.vue`
- Modify: `mateclaw-ui/src/i18n/locales/zh-CN.ts`
- Modify: `mateclaw-ui/src/i18n/locales/en-US.ts`

**Step 1: Update router**

In `mateclaw-ui/src/router/index.ts`, change the authenticated root redirect:

```ts
redirect: '/home',
```

Add the child route before `chat`:

```ts
{
  path: 'home',
  name: 'Home',
  component: () => import('@/views/Home/index.vue'),
  meta: { title: 'Home', requiredCapability: 'chat' },
},
```

Keep the existing `/dashboard` child route unchanged for direct URL compatibility.

**Step 2: Update sidebar**

In `mateclaw-ui/src/views/layout/MainLayout.vue`, replace the visible dashboard item in the `core` group with:

```ts
{
  path: '/home',
  label: t('nav.home', 'Home'),
  icon: `<svg width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><path d="M3 11.5 12 4l9 7.5"/><path d="M5 10.5V20h14v-9.5"/><path d="M9 20v-6h6v6"/></svg>`,
  requiredCapability: 'chat',
},
```

Do not add a second visible dashboard item.

**Step 3: Update active matching if needed**

`isNavItemActive()` already handles exact paths and does not need a special case for `/home`.

**Step 4: Add locale labels**

In `mateclaw-ui/src/i18n/locales/zh-CN.ts`, add:

```ts
home: '首页',
```

In `mateclaw-ui/src/i18n/locales/en-US.ts`, add:

```ts
home: 'Home',
```

**Step 5: Run navigation test**

Run: `cd mateclaw-ui && npx vitest run src/views/Home/__tests__/homeNavigation.test.ts`

Expected: PASS after `Home/index.vue` exists or route import string is present. If the build resolver complains before the file exists, continue to Task 4 and rerun.

### Task 4: Create Home Page Skeleton With Stable Data Loading

**Files:**
- Create: `mateclaw-ui/src/views/Home/index.vue`

**Step 1: Add script state and data loading**

Create `Home/index.vue` with this starting script:

```vue
<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { useI18n } from 'vue-i18n'
import { useRouter } from 'vue-router'
import { VideoPlay } from '@element-plus/icons-vue'
import { agentApi, dashboardApi } from '@/api'
import SkillIcon from '@/components/skill/SkillIcon.vue'
import type { Agent } from '@/types'

interface HomeBanner {
  id: string
  title: string
  subtitle: string
  eyebrow?: string
  demoVideoUrl?: string
  clientDownloadUrl?: string
  browserPluginDownloadUrl?: string
}

const { t } = useI18n()
const router = useRouter()

const banners = ref<HomeBanner[]>([
  {
    id: 'demo',
    eyebrow: 'MateClaw',
    title: t('home.banner.title'),
    subtitle: t('home.banner.subtitle'),
    demoVideoUrl: '',
    clientDownloadUrl: '',
    browserPluginDownloadUrl: '',
  },
])

const activeBannerIndex = ref(0)
const employees = ref<Agent[]>([])
const recentRuns = ref<any[]>([])
const loadingEmployees = ref(false)
const loadingRuns = ref(false)
const showVideoModal = ref(false)
const showEnvironmentPrompt = ref(false)

const activeBanner = computed(() => banners.value[activeBannerIndex.value] || banners.value[0])

const isClientEnvironment = computed(() => {
  const w = window as any
  const ua = navigator.userAgent || ''
  return Boolean(w.electronAPI || w.__TAURI__ || /Electron/i.test(ua))
})

onMounted(async () => {
  await Promise.all([loadEmployees(), loadRecentRuns()])
})

async function loadEmployees() {
  loadingEmployees.value = true
  try {
    const res: any = await agentApi.list({ enabled: true })
    employees.value = (res.data || res || []).slice(0, 8)
  } catch {
    employees.value = []
  } finally {
    loadingEmployees.value = false
  }
}

async function loadRecentRuns() {
  loadingRuns.value = true
  try {
    const res: any = await dashboardApi.recentRuns(8)
    recentRuns.value = res.data || res || []
  } catch {
    recentRuns.value = []
  } finally {
    loadingRuns.value = false
  }
}

function openDemoVideo(event: MouseEvent) {
  event.stopPropagation()
  if (!activeBanner.value.demoVideoUrl) return
  showVideoModal.value = true
}

function onBannerClick() {
  showEnvironmentPrompt.value = true
}

function startChat(agent: Agent) {
  router.push({
    path: '/chat',
    query: {
      agentId: String(agent.id),
      action: 'newChat',
    },
  })
}

function shortText(value: string | undefined, limit: number, fallback = '') {
  const text = (value || fallback || '').trim()
  if (text.length <= limit) return text
  return `${text.slice(0, limit)}...`
}

function employeeRole(agent: Agent) {
  const firstTag = (agent.tags || '').split(',').map((tag) => tag.trim()).filter(Boolean)[0]
  return shortText(firstTag, 10, t('home.market.defaultRole'))
}

function employeeGoal(agent: Agent) {
  return shortText(agent.systemPrompt || agent.description, 14, t('home.market.defaultGoal'))
}

function employeeDesc(agent: Agent) {
  return shortText(agent.description || agent.systemPrompt, 30, t('home.market.defaultDesc'))
}

function formatTime(value: string | undefined) {
  if (!value) return '-'
  return new Date(value).toLocaleString()
}

function calcDuration(run: any) {
  if (!run.startedAt || !run.finishedAt) return '-'
  const ms = new Date(run.finishedAt).getTime() - new Date(run.startedAt).getTime()
  if (!Number.isFinite(ms) || ms < 0) return '-'
  if (ms < 1000) return `${ms}ms`
  return `${(ms / 1000).toFixed(1)}s`
}
</script>
```

**Step 2: Keep strings temporary only where i18n is not yet added**

The page may reference `home.*` keys now. Add the keys in Task 6 before final verification.

### Task 5: Implement Scheme A Template And Styles

**Files:**
- Modify: `mateclaw-ui/src/views/Home/index.vue`

**Step 1: Add template**

Use this structure:

```vue
<template>
  <div class="mc-page-shell home-shell">
    <div class="mc-page-frame home-frame">
      <div class="mc-page-inner home-inner">
        <button class="home-banner" type="button" @click="onBannerClick">
          <div class="home-banner__copy">
            <div class="home-banner__eyebrow">{{ activeBanner.eyebrow || t('home.banner.eyebrow') }}</div>
            <h1>{{ activeBanner.title }}</h1>
            <p>{{ activeBanner.subtitle }}</p>
          </div>
          <button
            v-if="activeBanner.demoVideoUrl"
            class="home-banner__play"
            type="button"
            @click="openDemoVideo"
          >
            <el-icon><VideoPlay /></el-icon>
            {{ t('home.banner.watchDemo') }}
          </button>
        </button>

        <section class="home-section">
          <div class="home-section__head">
            <div>
              <h2>{{ t('home.market.title') }}</h2>
              <p>{{ t('home.market.subtitle') }}</p>
            </div>
          </div>
          <div v-if="employees.length" class="employee-grid">
            <button
              v-for="agent in employees"
              :key="agent.id"
              class="employee-card"
              type="button"
              @click="startChat(agent)"
            >
              <span class="employee-card__icon">
                <SkillIcon :value="agent.icon" :size="30" :fallback="'🤖'" />
              </span>
              <span class="employee-card__name">{{ agent.name }}</span>
              <span class="employee-card__role">{{ employeeRole(agent) }}</span>
              <span class="employee-card__goal">{{ employeeGoal(agent) }}</span>
              <span class="employee-card__desc">{{ employeeDesc(agent) }}</span>
              <span class="employee-card__cta">{{ t('home.market.startChat') }}</span>
            </button>
          </div>
          <div v-else class="home-empty">
            {{ loadingEmployees ? t('common.loading') : t('home.market.empty') }}
          </div>
        </section>

        <section class="home-section">
          <div class="home-section__head">
            <div>
              <h2>{{ t('home.runs.title') }}</h2>
              <p>{{ t('home.runs.subtitle') }}</p>
            </div>
          </div>
          <div class="home-runs">
            <table v-if="recentRuns.length">
              <thead>
                <tr>
                  <th>{{ t('home.runs.columns.time') }}</th>
                  <th>{{ t('home.runs.columns.job') }}</th>
                  <th>{{ t('home.runs.columns.status') }}</th>
                  <th>{{ t('home.runs.columns.trigger') }}</th>
                  <th>{{ t('home.runs.columns.duration') }}</th>
                </tr>
              </thead>
              <tbody>
                <tr v-for="run in recentRuns" :key="run.id">
                  <td>{{ formatTime(run.startedAt) }}</td>
                  <td>{{ run.jobName || `#${run.cronJobId}` }}</td>
                  <td><span class="run-status">{{ run.status }}</span></td>
                  <td>{{ run.triggerType || '-' }}</td>
                  <td>{{ calcDuration(run) }}</td>
                </tr>
              </tbody>
            </table>
            <div v-else class="home-empty">
              {{ loadingRuns ? t('common.loading') : t('home.runs.empty') }}
            </div>
          </div>
        </section>
      </div>
    </div>

    <div v-if="showVideoModal" class="home-modal-backdrop" @click.self="showVideoModal = false">
      <section class="home-modal" role="dialog" aria-modal="true">
        <button class="home-modal__close" type="button" @click="showVideoModal = false">{{ t('common.close') }}</button>
        <video v-if="activeBanner.demoVideoUrl" :src="activeBanner.demoVideoUrl" controls autoplay></video>
      </section>
    </div>

    <div v-if="showEnvironmentPrompt" class="home-modal-backdrop" @click.self="showEnvironmentPrompt = false">
      <section class="home-modal home-modal--prompt" role="dialog" aria-modal="true">
        <button class="home-modal__close" type="button" @click="showEnvironmentPrompt = false">{{ t('common.close') }}</button>
        <h2>{{ isClientEnvironment ? t('home.prompt.clientTitle') : t('home.prompt.webTitle') }}</h2>
        <p>{{ isClientEnvironment ? t('home.prompt.clientDesc') : t('home.prompt.webDesc') }}</p>
        <a
          v-if="isClientEnvironment && activeBanner.browserPluginDownloadUrl"
          class="home-modal__action"
          :href="activeBanner.browserPluginDownloadUrl"
          target="_blank"
          rel="noreferrer"
        >{{ t('home.prompt.downloadPlugin') }}</a>
        <a
          v-else-if="!isClientEnvironment && activeBanner.clientDownloadUrl"
          class="home-modal__action"
          :href="activeBanner.clientDownloadUrl"
          target="_blank"
          rel="noreferrer"
        >{{ t('home.prompt.downloadClient') }}</a>
      </section>
    </div>
  </div>
</template>
```

**Step 2: Add scoped styles**

Use `var(--mc-*)` tokens. Keep the page within the existing scroll frame:

- `.home-frame` height: `min(calc(100vh - 28px), 100%)`
- `.home-inner` scrollable column
- `.home-banner` stable min-height around `220px`
- `.employee-grid` responsive 4-column desktop, 2-column tablet, 1-column mobile
- cards use no more than `12px` radius and existing surface colors
- hover reveals `.employee-card__cta`

**Step 3: Run behavior contract test**

Run: `cd mateclaw-ui && npx vitest run src/views/Home/__tests__/homePageContract.test.ts`

Expected: PASS.

### Task 6: Add Home i18n Copy

**Files:**
- Modify: `mateclaw-ui/src/i18n/locales/zh-CN.ts`
- Modify: `mateclaw-ui/src/i18n/locales/en-US.ts`

**Step 1: Add Chinese copy**

Add under the top-level locale object:

```ts
home: {
  banner: {
    eyebrow: '首页',
    title: '让数字员工开始工作',
    subtitle: '选择合适的员工进入对话，或查看最近的自动任务执行。',
    watchDemo: '查看演示效果',
  },
  market: {
    title: '数字员工市场',
    subtitle: '推荐可直接开始协作的数字员工。',
    defaultRole: '数字员工',
    defaultGoal: '协助完成任务',
    defaultDesc: '选择后即可进入对话并开始协作。',
    startChat: '开始对话',
    empty: '暂无可用数字员工',
  },
  runs: {
    title: '最近定时任务执行',
    subtitle: '查看自动任务最近的执行状态。',
    empty: '暂无执行记录',
    columns: {
      time: '时间',
      job: '任务',
      status: '状态',
      trigger: '触发方式',
      duration: '耗时',
    },
  },
  prompt: {
    webTitle: '需要下载客户端',
    webDesc: '此功能需下载客户端才可使用。',
    clientTitle: '检查浏览器插件',
    clientDesc: '请确保已安装浏览器插件。',
    downloadClient: '下载客户端',
    downloadPlugin: '下载浏览器插件',
  },
}
```

**Step 2: Add English copy**

Add equivalent English strings:

```ts
home: {
  banner: {
    eyebrow: 'Home',
    title: 'Put digital employees to work',
    subtitle: 'Pick an employee to start a chat, or review recent automated task runs.',
    watchDemo: 'Watch demo',
  },
  market: {
    title: 'Digital Employee Market',
    subtitle: 'Recommended employees ready to collaborate.',
    defaultRole: 'Employee',
    defaultGoal: 'Help with tasks',
    defaultDesc: 'Select one to start a new conversation.',
    startChat: 'Start chat',
    empty: 'No available digital employees',
  },
  runs: {
    title: 'Recent Scheduled Runs',
    subtitle: 'Review the latest automated task execution status.',
    empty: 'No run records yet',
    columns: {
      time: 'Time',
      job: 'Job',
      status: 'Status',
      trigger: 'Trigger',
      duration: 'Duration',
    },
  },
  prompt: {
    webTitle: 'Client required',
    webDesc: 'This feature requires the desktop client.',
    clientTitle: 'Check browser plugin',
    clientDesc: 'Please make sure the browser plugin is installed.',
    downloadClient: 'Download client',
    downloadPlugin: 'Download browser plugin',
  },
}
```

**Step 3: Rerun navigation test**

Run: `cd mateclaw-ui && npx vitest run src/views/Home/__tests__/homeNavigation.test.ts`

Expected: PASS.

### Task 7: Verify Chat Handoff

**Files:**
- Verify: `mateclaw-ui/src/views/ChatConsole.vue`
- Verify: `mateclaw-ui/src/views/Home/index.vue`

**Step 1: Confirm route query support exists**

Check `ChatConsole.vue` contains:

```ts
const agentId = route.query.agentId ? String(route.query.agentId) : ''
```

and:

```ts
if (action === 'newChat') {
  newConversation()
}
```

Expected: existing code already supports this handoff.

**Step 2: Add a focused contract assertion if needed**

If home behavior test does not cover the query shape clearly, add assertions that `Home/index.vue` contains `agentId: String(agent.id)` and `action: 'newChat'`.

### Task 8: Run Final Verification

**Files:**
- Verify: `mateclaw-ui/src/views/Home/index.vue`
- Verify: `mateclaw-ui/src/router/index.ts`
- Verify: `mateclaw-ui/src/views/layout/MainLayout.vue`
- Verify: locale files and tests

**Step 1: Run focused tests**

Run:

```bash
cd mateclaw-ui && npx vitest run src/views/Home/__tests__/homeNavigation.test.ts src/views/Home/__tests__/homePageContract.test.ts
```

Expected: PASS.

**Step 2: Run build**

Run:

```bash
cd mateclaw-ui && npm run build
```

Expected: PASS.

**Step 3: Optional visual verification**

Start the dev server:

```bash
cd mateclaw-ui && npm run dev
```

Open `/home` in the in-app browser and check:

- first viewport shows banner and digital employee cards
- no visible dashboard item in sidebar
- employee card hover reveals `开始对话`
- clicking a card navigates to `/chat?agentId=...&action=newChat`
- banner video button appears only when `demoVideoUrl` is configured
- banner prompt copy changes between web and client-like environment

**Step 4: Inspect final diff**

Run:

```bash
git diff -- mateclaw-ui/src/views/Home/index.vue mateclaw-ui/src/views/Home/__tests__/homeNavigation.test.ts mateclaw-ui/src/views/Home/__tests__/homePageContract.test.ts mateclaw-ui/src/router/index.ts mateclaw-ui/src/views/layout/MainLayout.vue mateclaw-ui/src/i18n/locales/zh-CN.ts mateclaw-ui/src/i18n/locales/en-US.ts
```

Expected: diff only contains the new Home page, its tests, route/nav/i18n changes, and no unrelated existing workspace changes.
