import { describe, expect, it } from 'vitest'
import layout from '../MainLayout.vue?raw'
import zh from '../../../i18n/locales/zh-CN.ts?raw'
import en from '../../../i18n/locales/en-US.ts?raw'

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
