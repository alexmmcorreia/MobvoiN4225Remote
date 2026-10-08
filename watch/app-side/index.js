import { BaseSideService, settingsLib } from '@zeppos/zml/base-side'

const BASE = 'http://127.0.0.1:18765'
let _fetch = null

try {
  const network = require('@zos/app-side/network')
  _fetch = network && network.fetch ? network.fetch : null
} catch (_e) {}

function plainSetting(key) {
  try {
    const raw = settingsLib.getItem(key)
    if (raw === null || raw === undefined) return ''
    try {
      const parsed = JSON.parse(raw)
      if (typeof parsed === 'string' || typeof parsed === 'number') return String(parsed)
      if (parsed && parsed.value !== undefined) return String(parsed.value)
    } catch (_e) {}
    return String(raw).replace(/^"|"$/g, '')
  } catch (_e) {
    return ''
  }
}

async function requestNative(path, method, payload) {
  if (!_fetch) throw new Error('network unavailable')
  const key = plainSetting('pairing_code').trim()
  if (!key) throw new Error('pairing code missing')

  const options = {
    url: BASE + path,
    method,
    headers: {
      'Content-Type': 'application/json',
      'X-Training-Hub-Key': key,
    },
  }
  if (payload !== undefined && payload !== null) {
    options.body = JSON.stringify(payload)
  }

  const response = await _fetch(options)
  if (!response) throw new Error('Training Hub unavailable')
  const text = await response.text()
  let data = {}
  try {
    data = text ? JSON.parse(text) : {}
  } catch (_e) {
    throw new Error('invalid Training Hub response')
  }
  if (response.status < 200 || response.status >= 300) {
    throw new Error(data.error || ('HTTP ' + response.status))
  }
  return data
}

AppSideService(
  BaseSideService({
    onInit() {
      if (!_fetch && typeof this.fetch === 'function') {
        _fetch = this.fetch.bind(this)
      }
      console.log('[training-hub-side] ready')
    },

    async onRequest(req, res) {
      try {
        const method = req && req.method
        const params = (req && req.params) || {}

        if (method === 'GET_STATE') {
          res(null, await requestNative('/v1/watch/state', 'GET'))
          return
        }
        if (method === 'ACTION') {
          res(null, await requestNative('/v1/watch/action', 'POST', params))
          return
        }
        if (method === 'TELEMETRY') {
          res(null, await requestNative('/v1/watch/telemetry', 'POST', params))
          return
        }
        if (method === 'DAILY') {
          res(null, await requestNative('/v1/watch/daily', 'POST', params))
          return
        }
        if (method === 'HISTORY') {
          res(null, await requestNative('/v1/watch/history', 'POST', params))
          return
        }
        if (method === 'PING') {
          if (!_fetch) throw new Error('network unavailable')
          const response = await _fetch({ url: BASE + '/v1/watch/ping', method: 'GET' })
          res(null, JSON.parse(await response.text()))
          return
        }
        res(null, { ok: false, error: 'unknown method' })
      } catch (error) {
        res(null, {
          ok: false,
          error: error && error.message ? error.message : String(error),
        })
      }
    },

    onSettingsChange() {},
    onRun() {},
    onDestroy() {},
  }),
)
