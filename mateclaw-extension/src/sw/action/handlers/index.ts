// Barrel re-exports for the per-kind ActionExecutor handlers.
//
// All six handler factories landed via Phase 2 Wave 1 + Wave 2; the SW
// composes them in `sw/index.ts`. This barrel exists so future call sites
// (e.g. a future ActionExecutor factory in tests) can import a single
// symbol rather than six.

export { navigateHandler }   from './navigate'
export { clickHandler }      from './click'
export { typeHandler }       from './type'
export { scrollHandler }     from './scroll'
export { moveMouseHandler }  from './move_mouse'
export { waitHandler }       from './wait'
