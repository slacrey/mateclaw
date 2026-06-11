/// <reference types="vite/client" />

interface ImportMetaEnv {
  /** Chrome extension ID the admin UI targets for one-click browser pairing.
   *  Defaults in code to the unpacked extension's deterministic ID; override
   *  for a future Chrome Web Store listing. See docs/specs/phase-3.1-contract.md §0. */
  readonly VITE_MATECLAW_EXTENSION_ID?: string
}

interface ImportMeta {
  readonly env: ImportMetaEnv
}

interface Window {
  mateclawDesktop?: {
    versions?: {
      electron?: string
      chrome?: string
      node?: string
    }
  }
}
