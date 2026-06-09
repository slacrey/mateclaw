# Phone Registration Design

## Goal

Add self-service registration to MateClaw using a phone number, fixed verification code `888888`, and password. After successful registration, the user should be authenticated and sent to `/dashboard`.

## Selected Approach

Use the smallest integration that fits the current system:

- Treat the phone number as `mate_user.username`.
- Keep the existing password login model by storing a BCrypt password.
- Add an anonymous `POST /api/v1/auth/register` endpoint.
- Automatically add the new user to the default workspace (`id = 1`) as `member`.
- Return the same `LoginResponse` shape as login so the frontend can reuse token storage.

This avoids schema changes while keeping the future path open for a dedicated `phone` column or real SMS provider.

## Backend Design

Create a `RegisterRequest` DTO with:

- `phone`
- `code`
- `password`
- optional `nickname`

Add `AuthService.register(RegisterRequest request)`:

1. Normalize and validate the phone number.
2. Require verification code `888888`.
3. Require a non-blank password.
4. Reject duplicate phone numbers by checking `username`.
5. Create a regular `mate_user` with role `user`, enabled `true`, nickname defaulting to the phone number, and BCrypt password.
6. Add the user to default workspace `1` as `member`.
7. Return `LoginResponse` with a freshly generated JWT.

Expose the method through `AuthController` at `POST /api/v1/auth/register`, and update `SecurityConfig` so the endpoint is anonymous like `/auth/login`.

The register path should share anonymous endpoint protection with login. The existing `LoginRateLimitFilter` can be broadened to cover both `/api/v1/auth/login` and `/api/v1/auth/register`.

## Frontend Design

Update `mateclaw-ui/src/views/Login.vue` to support a compact login/register mode switch.

Login mode stays unchanged. Register mode shows:

- phone number
- verification code
- password
- confirm password

The verification button should not call an SMS API yet. It can show the development code or populate `888888` so the behavior is explicit during this fixed-code phase.

Add `authApi.register()` in `mateclaw-ui/src/api/index.ts`. On success, store `token`, `userId`, `username`, and `role` the same way login does, refresh workspace capabilities, then route to `/dashboard`.

## Data Flow

1. User opens `/login` and switches to register mode.
2. User submits phone, code `888888`, password, and confirmation.
3. Frontend validates required fields and matching passwords.
4. Frontend posts to `/api/v1/auth/register`.
5. Backend creates user and default workspace membership.
6. Backend returns `LoginResponse`.
7. Frontend saves auth state and navigates to `/dashboard`.

## Error Handling

Use existing `MateClawException` and `R<T>` response conventions:

- invalid phone number: `err.auth.invalid_phone`
- invalid verification code: `err.auth.invalid_verification_code`
- duplicate phone number: reuse or mirror `err.auth.username_exists`
- missing password: reuse `err.auth.password_required`
- default workspace membership failure: fail registration unless the membership already exists

The UI should surface backend messages through the existing axios interceptor error path.

## Testing

Backend tests should cover:

- successful registration returns a token and user identity
- verification code must equal `888888`
- duplicate phone number is rejected
- successful registration adds workspace `1` membership as `member`
- `/api/v1/auth/register` is permitted without a token

Frontend tests should cover:

- password confirmation mismatch blocks submit
- successful registration stores the returned session and routes to `/dashboard`

If the frontend test harness is too costly for this change, at minimum run the existing type/build checks plus focused backend tests.
