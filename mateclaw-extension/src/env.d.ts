/// <reference types="vite/client" />

// Ambient module declaration so TS picks up Vue Single-File Components when
// they are imported as `./App.vue`. Mirrors the standard Vue + Vite + TS
// project template recommendation.
declare module '*.vue' {
  import type { DefineComponent } from 'vue'
  // eslint-disable-next-line @typescript-eslint/no-explicit-any
  const component: DefineComponent<{}, {}, any>
  export default component
}
