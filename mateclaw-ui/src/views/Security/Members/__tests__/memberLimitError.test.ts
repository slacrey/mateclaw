import { describe, expect, it } from 'vitest'
import { isMemberLimitError } from '../memberLimitError'

describe('isMemberLimitError', () => {
  it('detects backend member limit responses by reason, key, or localized message', () => {
    expect(isMemberLimitError({
      response: { data: { data: { reason: 'MEMBER_LIMIT_EXCEEDED' } } },
    })).toBe(true)

    expect(isMemberLimitError({
      response: { data: { msg: 'err.workspace.member_limit_exceeded' } },
    })).toBe(true)

    expect(isMemberLimitError(new Error('超过最大团队成员数量'))).toBe(true)
    expect(isMemberLimitError(new Error('Maximum team member limit exceeded'))).toBe(true)
  })

  it('ignores unrelated add-member errors', () => {
    expect(isMemberLimitError(new Error('用户名不能为空'))).toBe(false)
    expect(isMemberLimitError({ response: { data: { msg: 'Forbidden' } } })).toBe(false)
    expect(isMemberLimitError(null)).toBe(false)
  })
})
