const MEMBER_LIMIT_REASON = 'MEMBER_LIMIT_EXCEEDED'
const MEMBER_LIMIT_KEY = 'err.workspace.member_limit_exceeded'

const MEMBER_LIMIT_MESSAGES = [
  '超过最大团队成员数量',
  'Maximum team member limit exceeded',
]

type ErrorLike = {
  message?: unknown
  msg?: unknown
  response?: {
    data?: {
      msg?: unknown
      data?: {
        reason?: unknown
      }
    }
  }
}

export function isMemberLimitError(error: unknown): boolean {
  if (!error || typeof error !== 'object') return false

  const err = error as ErrorLike
  const responseData = err.response?.data
  const reason = responseData?.data?.reason
  const messages = [responseData?.msg, err.msg, err.message]
    .filter((message): message is string => typeof message === 'string')

  return reason === MEMBER_LIMIT_REASON
    || messages.some((message) => message === MEMBER_LIMIT_KEY || MEMBER_LIMIT_MESSAGES.includes(message))
}
