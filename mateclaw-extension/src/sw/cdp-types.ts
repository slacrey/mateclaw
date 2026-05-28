export interface CDP {
  'Page.navigate': {
    params: {
      url: string
      referrer?: string
      transitionType?: string
    }
    result: {
      frameId: string
      loaderId?: string
      errorText?: string
    }
  }
  'Input.dispatchMouseEvent': {
    params: {
      type: 'mousePressed' | 'mouseReleased' | 'mouseMoved' | 'mouseWheel'
      x: number
      y: number
      button?: 'none' | 'left' | 'middle' | 'right' | 'back' | 'forward'
      buttons?: number
      clickCount?: number
      deltaX?: number
      deltaY?: number
      modifiers?: number
    }
    result: Record<string, never>
  }
  'Input.dispatchMouseWheelEvent': {
    params: {
      type: 'mouseWheel'
      x: number
      y: number
      deltaX: number
      deltaY: number
      /** Pointer modifiers; we always send 0 for now. */
      modifiers?: number
    }
    result: {}
  }
  'Input.dispatchKeyEvent': {
    params: {
      type: 'keyDown' | 'keyUp' | 'rawKeyDown' | 'char'
      text?: string
      key?: string
      code?: string
      windowsVirtualKeyCode?: number
      nativeVirtualKeyCode?: number
      unmodifiedText?: string
      modifiers?: number
    }
    result: Record<string, never>
  }
  'Page.captureScreenshot': {
    params: {
      format?: 'jpeg' | 'png' | 'webp'
      quality?: number
    }
    result: {
      data: string
    }
  }
  'Runtime.evaluate': {
    params: {
      expression: string
      awaitPromise?: boolean
      returnByValue?: boolean
      userGesture?: boolean
    }
    result: {
      result: {
        type: string
        value?: unknown
        description?: string
        objectId?: string
      }
      exceptionDetails?: unknown
    }
  }
}
