import { BaseApp } from '@zeppos/zml/base-app'

App(
  BaseApp({
    onCreate() {
      console.log('[training-hub] app created')
    },
    onDestroy() {
      console.log('[training-hub] app destroyed')
    },
  }),
)
