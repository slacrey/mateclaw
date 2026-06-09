# Account Expiry and Team Limits Design

Date: 2026-05-29
Status: Approved design

## Context

MateClaw currently has:

- `mate_user` as the account table.
- `mate_workspace` as the resource isolation boundary.
- `mate_workspace_member` as the membership and role table.
- Phone registration that creates a user, returns a JWT, and currently joins the user to the default workspace.
- A Settings / Members page that can add a username to the current workspace, creating the user when a password is supplied.

The new requirement treats each newly registered account as its own team, limits the initial team to one child account, and introduces account expiry.

## Decisions

- Reuse `Workspace` as the team model.
- Store account expiry on `mate_user.expires_at`.
- Use `null` expiry to mean permanent validity.
- Existing accounts remain permanently valid after migration.
- New registered owner accounts receive 30 days from registration time.
- New child accounts inherit the current team owner's expiry.
- Initial team size is `owner + 1 non-owner member`.
- Business QR code is a frontend static asset, for example `public/business-qr.png`.
- Expired users can log in, but core business APIs are blocked after login.

## Data Model

Add `expires_at` to `mate_user`:

- Java type: `LocalDateTime`.
- Database type: timestamp/datetime with seconds precision.
- Nullable: yes.
- Meaning:
  - `null`: permanent.
  - non-null and before current time: expired.
  - non-null and at or after current time: active.

Update H2 and MySQL migrations, plus baseline schema files used for fresh installs or tests. The migration does not backfill historical rows.

## Registration Flow

Phone registration changes from "join the default workspace" to "create a dedicated team workspace".

On successful registration:

1. Normalize and validate phone.
2. Validate the fixed registration code.
3. Reject duplicate username.
4. Create `mate_user` with `expires_at = now + 30 days`.
5. Create a workspace named from nickname or phone, for example `{displayName} 的团队`.
6. Make the new user the workspace `owner`.
7. Return login data with token, role, `expiresAt`, `expired`, and optionally the new workspace id.

The workspace slug should continue using the existing unique slug generation.

## Child Accounts

Child accounts are added through the existing members management UI and API.

For the first version, adding an existing user by username should be rejected. This avoids ambiguous expiry semantics when one user belongs to multiple teams. The user should create a fresh username/phone for the child account instead.

When the supplied username does not exist:

1. Require password as today.
2. Create the new user.
3. Set the new user's `expires_at` to the workspace owner's `expires_at`.
4. Add the user as a non-owner member.

If the owner's expiry is `null`, the child account is permanent too.

## Team Size Limit

The default initial team allows one child account:

- Owner does not count against the child limit.
- Non-owner members count against the child limit.
- The default max child count is `1`.

Enforcement lives in `WorkspaceService.addMember` or a small entitlement service it calls. When the limit is reached, throw a structured error:

- key: `err.workspace.member_limit_exceeded`
- HTTP status: `409`
- message: `超过最大团队成员数量`

The frontend members page catches this key and opens a business contact modal with the QR code.

## Entitlement Service

Add a small service, tentatively `AccountEntitlementService`, to keep expiry and limits in one place.

Responsibilities:

- `isExpired(UserEntity user)`.
- `requireActive(UserEntity user)`.
- `resolveOwnerExpiry(Long workspaceId)`.
- `assertCanAddSubAccount(Long workspaceId)`.
- Optionally build a standard expired response payload.

This keeps controllers and filters simple and leaves room for a future subscription table without spreading entitlement logic through the app.

## Expiry Access Control

Login remains allowed for expired accounts. The response includes expiry state so the frontend can show the blocking modal.

After authentication, expired accounts are blocked from core business APIs by a filter or interceptor that runs after JWT authentication and before controllers.

Allowlist:

- `POST /api/v1/auth/login`
- registration endpoints
- account status endpoint such as `GET /api/v1/auth/me` or `GET /api/v1/account/me`
- workspace list and access endpoints required to initialize the app shell
- static assets and health endpoints

Blocked responses use the existing `R` shape:

```json
{
  "code": 403,
  "msg": "账号已过期",
  "data": {
    "reason": "ACCOUNT_EXPIRED",
    "expiresAt": "2026-06-28T12:00:00"
  }
}
```

Use i18n key `err.account.expired`.

## Frontend Account State

Add an account state source, preferably a dedicated `useAccountStore`.

The store owns:

- `expiresAt`
- `expired`
- formatted expiry text
- `fetchAccount()`
- `markExpired(payload?)`

Login and registration write the account state immediately. App startup refreshes it from the account status endpoint.

## UI

### Header Expiry Display

Show account validity in the top-right area of `MainLayout`.

States:

- Permanent: `永久有效`.
- Active with expiry: `有效期至 YYYY-MM-DD HH:mm:ss`.
- Expired: `已过期`.

Use a compact badge consistent with the current navigation/header style.

### Expired Account Modal

Mount globally in `App.vue` or `MainLayout.vue`.

Behavior:

- Opens when `accountStore.expired` is true.
- No close button.
- Cannot be dismissed by Escape or overlay click.
- Remains visible across route changes.
- Shows copy: `账号已过期，请扫码联系商务续费`.
- Shows the static business QR image.

Axios response handling should also detect `err.account.expired` and call `accountStore.markExpired()`.

### Member Limit Modal

In the members page, when adding a child account fails with `err.workspace.member_limit_exceeded`, show a dismissible modal:

- Title: `超过最大团队成员数量`.
- Copy: `请扫码联系商务开通更多成员`.
- QR: same static business QR image.

Other member-add failures continue to use toast messages.

## Testing

Backend tests:

- Registration gives new owner account an expiry roughly 30 days in the future.
- Registration creates a dedicated workspace and owner membership.
- Existing accounts with `expires_at = null` are treated as active.
- Child account creation inherits owner expiry.
- Adding a second non-owner member fails with `err.workspace.member_limit_exceeded`.
- Expired account can log in.
- Expired account can access account status and shell bootstrap endpoints.
- Expired account cannot access core business endpoints.

Frontend checks:

- `vue-tsc --noEmit`.
- If practical, unit-test the account store expiry formatting.
- Browser check:
  - login/register state receives expiry.
  - header badge renders active and expired states.
  - expired modal cannot be dismissed.
  - member limit modal shows the QR code.

Known project caveat: `pnpm build` currently depends on `../scripts/check-snowflake-precision.sh`, which is missing from the repo. Use `vue-tsc` and direct `vite build` to verify frontend code until that script issue is resolved.

## Rollout Notes

- Historical users remain valid because `expires_at` is nullable and left null.
- The first release should avoid adding existing users into a new team to prevent expiry conflicts.
- Future subscription work can move expiry and member limits from user/workspace logic into a dedicated subscription table while keeping `AccountEntitlementService` as the adapter boundary.
