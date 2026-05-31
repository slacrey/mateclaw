# Contact Support Menu Design

Date: 2026-05-31
Status: Approved design

## Context

MateClaw already has a global left sidebar in `mateclaw-ui/src/views/layout/MainLayout.vue`. The app also ships a static business QR asset at `mateclaw-ui/public/business-qr.svg`, currently used by the account-expiry modal.

The requested feature adds a customer-service entry so logged-in users can scan a business QR code and contact support.

## Decisions

- Add a `联系客服` / `Contact Support` action to the left sidebar.
- Make the entry visible to every logged-in user, independent of workspace capabilities.
- Place it in the existing `connect` group so it sits near external/channel-related actions.
- Treat it as an in-place action, not a router destination.
- Reuse `/business-qr.svg`.
- Use a dismissible modal with overlay click, close button, and Escape support.
- Preserve mobile behavior by closing the mobile sidebar after the action is clicked.
- Add Chinese and English i18n strings.

## UI

The sidebar entry uses the same `.nav-item` styling as route links. When expanded, it shows the label. When collapsed, the tooltip shows the label.

The modal contains:

- Title: `联系客服` / `Contact Support`.
- Description: ask the user to scan the QR code to contact business support.
- QR image: `/business-qr.svg`.
- Small hint text: business support will help with renewal, expansion, or service questions.
- Close button.

The modal follows the existing app surface style: soft panel background, rounded corners, subtle border, and token-based colors.

## Accessibility

- The sidebar action is a real `button`.
- The dialog uses `role="dialog"` and `aria-modal="true"`.
- The close button has an accessible label.
- Escape closes the dialog.
- The QR image uses localized alt text.

## Testing

Add a focused UI contract test that verifies:

- The layout defines a contact-support nav action.
- The layout opens a contact-support modal.
- The modal uses `/business-qr.svg`.
- Chinese and English locale files define the required strings.
