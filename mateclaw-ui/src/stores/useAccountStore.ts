import { acceptHMRUpdate, defineStore } from 'pinia'
import { ref } from 'vue'
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

export const useAccountStore = defineStore('account', () => {
  const expiresAt = ref<string | null>(readStoredExpiresAt())
  const expired = ref(readStoredExpired())

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
    applyStatus,
    markExpired,
    fetchAccount,
  }
})

if (import.meta.hot) {
  import.meta.hot.accept(acceptHMRUpdate(useAccountStore, import.meta.hot))
}
