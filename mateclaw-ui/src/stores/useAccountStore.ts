import { acceptHMRUpdate, defineStore } from 'pinia'
import { computed, ref } from 'vue'
import { authApi } from '@/api/index'
import type { AccountStatus, LoginResponse } from '@/types'

const EXPIRES_AT_KEY = 'mc-account-expires-at'
const EXPIRED_KEY = 'mc-account-expired'

type AccountStatusSource = Pick<AccountStatus, 'expiresAt' | 'expired'> | LoginResponse

function readStoredExpiresAt(): string | null {
  try {
    return localStorage.getItem(EXPIRES_AT_KEY)
  } catch {
    return null
  }
}

function readStoredExpired(): boolean {
  try {
    return localStorage.getItem(EXPIRED_KEY) === 'true'
  } catch {
    return false
  }
}

function persistStatus(nextExpiresAt: string | null, nextExpired: boolean) {
  try {
    if (nextExpiresAt) {
      localStorage.setItem(EXPIRES_AT_KEY, nextExpiresAt)
    } else {
      localStorage.removeItem(EXPIRES_AT_KEY)
    }
    localStorage.setItem(EXPIRED_KEY, String(nextExpired))
  } catch {
    /* ignore storage failures */
  }
}

function formatDateTime(value: string): string {
  const date = new Date(value)
  if (Number.isNaN(date.getTime())) return value

  const pad = (part: number) => String(part).padStart(2, '0')
  const year = date.getFullYear()
  const month = pad(date.getMonth() + 1)
  const day = pad(date.getDate())
  const hours = pad(date.getHours())
  const minutes = pad(date.getMinutes())
  const seconds = pad(date.getSeconds())
  return `${year}-${month}-${day} ${hours}:${minutes}:${seconds}`
}

export const useAccountStore = defineStore('account', () => {
  const expiresAt = ref<string | null>(readStoredExpiresAt())
  const expired = ref(readStoredExpired())

  const isPermanent = computed(() => !expired.value && !expiresAt.value)
  const expiryText = computed(() => {
    if (expired.value) return '已过期'
    if (!expiresAt.value) return '永久有效'
    return `有效期至 ${formatDateTime(expiresAt.value)}`
  })

  function applyStatus(status: AccountStatusSource) {
    expiresAt.value = status.expiresAt ?? null
    expired.value = Boolean(status.expired)
    persistStatus(expiresAt.value, expired.value)
  }

  function markExpired(payload?: { expiresAt?: string | null }) {
    if (payload && 'expiresAt' in payload) {
      expiresAt.value = payload.expiresAt ?? expiresAt.value
    }
    expired.value = true
    persistStatus(expiresAt.value, expired.value)
  }

  async function fetchAccount() {
    const res = await authApi.me()
    const data = ('data' in res ? res.data : res) as AccountStatus
    applyStatus(data)
    return data
  }

  return {
    expiresAt,
    expired,
    isPermanent,
    expiryText,
    applyStatus,
    markExpired,
    fetchAccount,
  }
})

if (import.meta.hot) {
  import.meta.hot.accept(acceptHMRUpdate(useAccountStore, import.meta.hot))
}
