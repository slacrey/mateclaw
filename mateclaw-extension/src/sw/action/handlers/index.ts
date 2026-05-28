// Barrel export for the per-kind ActionExecutor handlers.
//
// TODO(B3-B8): The real handler factories are landing via parallel Codex
// tasks (Codex 10 = B3 navigate; Codex 11 = B4/B5 click+type; Codex 12 =
// B6 scroll; Codex 13 = B8 wait; move_mouse falls out of WindMouse). When
// those commits merge, replace the stub registry below with real imports:
//
//   import { makeNavigateHandler } from './navigate'
//   import { makeClickHandler }    from './click'
//   import { makeTypeHandler }     from './type'
//   import { makeScrollHandler }   from './scroll'
//   import { makeMoveMouseHandler } from './move_mouse'
//   import { makeWaitHandler }     from './wait'
//
//   export function makeAllHandlers(deps: HandlerDeps): ActionHandlers {
//     return {
//       navigate:   makeNavigateHandler(deps),
//       click:      makeClickHandler(deps),
//       type:       makeTypeHandler(deps),
//       scroll:     makeScrollHandler(deps),
//       move_mouse: makeMoveMouseHandler(deps),
//       wait:       makeWaitHandler(deps),
//     }
//   }
//
// Until then, see index.ts where the SW wires a stub-only registry that
// throws ActionFailureError('UNKNOWN_KIND') on every kind. ActionExecutor's
// own UNKNOWN_KIND path is the more obvious place to look — we deliberately
// keep this barrel empty so a merge-time grep for TODO(B3-B8) lands here.

export {}
