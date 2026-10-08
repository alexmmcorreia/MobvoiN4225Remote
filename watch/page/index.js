import { createWidget, deleteWidget, widget, prop, align, text_style } from '@zos/ui'
import {
  HeartRate,
  Sleep,
  Step,
  Stress,
  BloodOxygen,
  BodyTemperature,
  Vibrator,
  VIBRATOR_SCENE_SHORT_MIDDLE,
  VIBRATOR_SCENE_NOTIFICATION,
} from '@zos/sensor'
import { setPageBrightTime, resetPageBrightTime } from '@zos/display'
import { BasePage } from '@zeppos/zml/base-page'

const W = 466
const BG = 0x000000
const CARD = 0x191919
const CARD_ACTIVE = 0x2b2b2b
const TEXT = 0xffffff
const MUTED = 0x9aa0a6
const ACCENT = 0x8ab4f8
const SUCCESS = 0x6ddc91
const WARNING = 0xffc857

let page = null
let widgets = []
let hrSensor = null
let hrCallback = null
let lastHrSentAt = 0
let pollTimer = null
let tickTimer = null
let vibrator = null
let stateReceivedAt = 0
let lastRestValue = 0

const vm = {
  connected: false,
  error: '',
  workout: null,
  heartRate: null,
  draftRpe: null,
  daily: null,
  busy: false,
}

function add(type, options) {
  const w = createWidget(type, options)
  widgets.push(w)
  return w
}

function clearWidgets() {
  widgets.forEach((w) => {
    try { deleteWidget(w) } catch (_e) {}
  })
  widgets = []
}

function fmt(value) {
  if (value === null || value === undefined || value === '') return '—'
  const n = Number(value)
  if (!Number.isNaN(n) && Math.abs(n - Math.round(n)) < 0.001) return String(Math.round(n))
  if (!Number.isNaN(n)) return n.toFixed(1)
  return String(value)
}

function restRemaining() {
  const base = Number(vm.workout?.restRemainingSec || 0)
  if (base <= 0) return 0
  const elapsed = Math.floor((Date.now() - stateReceivedAt) / 1000)
  return Math.max(0, base - elapsed)
}

function localDateKey() {
  const now = new Date()
  const y = now.getFullYear()
  const m = String(now.getMonth() + 1).padStart(2, '0')
  const d = String(now.getDate()).padStart(2, '0')
  return y + '-' + m + '-' + d
}

function fmtTime(seconds) {
  const sec = Math.max(0, Number(seconds) || 0)
  const m = Math.floor(sec / 60)
  const s = sec % 60
  return String(m).padStart(2, '0') + ':' + String(s).padStart(2, '0')
}

function safeVibrate(mode) {
  try {
    if (!vibrator) vibrator = new Vibrator()
    vibrator.start({ mode: mode || VIBRATOR_SCENE_SHORT_MIDDLE })
  } catch (_e) {}
}

function text(options) {
  return add(widget.TEXT, {
    color: TEXT,
    text_size: 24,
    align_h: align.CENTER_H,
    align_v: align.CENTER_V,
    text_style: text_style.NONE,
    ...options,
  })
}

function button(options) {
  return add(widget.BUTTON, {
    radius: 24,
    normal_color: CARD_ACTIVE,
    press_color: 0x444444,
    text_color: TEXT,
    text_size: 22,
    ...options,
  })
}

function render() {
  clearWidgets()
  add(widget.FILL_RECT, { x: 0, y: 0, w: W, h: W, color: BG })

  text({
    x: 78, y: 24, w: 310, h: 38,
    text: 'Training Hub',
    text_size: 27,
  })

  text({
    x: 30, y: 62, w: 280, h: 30,
    text: vm.connected ? 'Active 2 ligado' : (vm.error ? 'Sem ligação' : 'A ligar…'),
    text_size: 18,
    color: vm.connected ? SUCCESS : MUTED,
    align_h: align.LEFT,
  })

  text({
    x: 308, y: 58, w: 128, h: 38,
    text: vm.heartRate ? ('♥ ' + vm.heartRate) : '♥ —',
    text_size: 22,
    color: vm.heartRate ? SUCCESS : MUTED,
  })

  if (!vm.connected) {
    text({
      x: 45, y: 145, w: 376, h: 82,
      text: vm.error || 'A procurar o Training Hub no telemóvel…',
      text_size: 22,
      color: WARNING,
      text_style: text_style.WRAP,
    })
    text({
      x: 55, y: 245, w: 356, h: 82,
      text: 'No telemóvel: Training Hub > Mais > Active 2.\nConfirma o código nas definições da Mini App.',
      text_size: 18,
      color: MUTED,
      text_style: text_style.WRAP,
    })
    button({
      x: 133, y: 350, w: 200, h: 58,
      text: 'Tentar de novo',
      click_func: () => refreshState(true),
    })
    return
  }

  const s = vm.workout || {}
  if (s.cardio && s.cardio.active) {
    renderCardio(s.cardio)
    return
  }

  if (s.complete) {
    text({
      x: 60, y: 130, w: 346, h: 55,
      text: 'Treino concluído',
      text_size: 32,
      color: SUCCESS,
    })
    text({
      x: 65, y: 200, w: 336, h: 45,
      text: (s.completedSets || 0) + '/' + (s.totalSets || 0) + ' séries',
      text_size: 24,
    })
    renderDaily(275)
    button({
      x: 148, y: 380, w: 170, h: 55,
      text: 'Atualizar',
      click_func: () => refreshState(true),
    })
    return
  }

  if (!s.exerciseName) {
    text({
      x: 55, y: 140, w: 356, h: 75,
      text: 'Sem treino ativo no Training Hub',
      text_size: 25,
      text_style: text_style.WRAP,
    })
    renderDaily(235)
    button({
      x: 148, y: 380, w: 170, h: 55,
      text: 'Atualizar',
      click_func: () => refreshState(true),
    })
    return
  }

  text({
    x: 36, y: 101, w: 394, h: 55,
    text: s.exerciseName,
    text_size: 28,
    text_style: text_style.WRAP,
  })

  const setLabel =
    'Série ' + ((s.setIndex || 0) + 1) + '/' + (s.setCount || 0) +
    '  ·  ' + fmt(s.load) + ' kg × ' + fmt(s.reps)
  text({
    x: 44, y: 157, w: 378, h: 38,
    text: setLabel,
    text_size: 22,
    color: ACCENT,
  })

  const rpeText = vm.draftRpe === null ? 'RPE —' : ('RPE ' + fmt(vm.draftRpe))
  text({
    x: 150, y: 198, w: 166, h: 38,
    text: rpeText,
    text_size: 24,
  })

  const rest = restRemaining()
  if (rest > 0) {
    text({
      x: 110, y: 235, w: 246, h: 60,
      text: fmtTime(rest),
      text_size: 43,
      color: rest <= 10 ? WARNING : SUCCESS,
    })
    text({
      x: 160, y: 292, w: 146, h: 28,
      text: 'DESCANSO',
      text_size: 17,
      color: MUTED,
    })
    button({
      x: 72, y: 334, w: 145, h: 58,
      text: '+30s',
      click_func: () => sendAction('EXTEND_REST', { seconds: 30 }),
    })
    button({
      x: 249, y: 334, w: 145, h: 58,
      text: 'Saltar',
      click_func: () => sendAction('SKIP_REST'),
    })
  } else {
    button({
      x: 67, y: 234, w: 112, h: 52,
      text: 'RPE −',
      click_func: () => {
        const base = vm.draftRpe === null ? 7 : vm.draftRpe
        vm.draftRpe = Math.max(5, base - 0.5)
        render()
      },
    })
    button({
      x: 287, y: 234, w: 112, h: 52,
      text: 'RPE +',
      click_func: () => {
        const base = vm.draftRpe === null ? 7 : vm.draftRpe
        vm.draftRpe = Math.min(10, base + 0.5)
        render()
      },
    })
    button({
      x: 98, y: 304, w: 270, h: 74,
      text: vm.busy ? 'A gravar…' : '✓ SÉRIE FEITA',
      normal_color: 0x1f6f43,
      press_color: 0x2b8a57,
      text_size: 25,
      click_func: () => {
        if (vm.busy) return
        sendAction('COMPLETE_CURRENT', {
          load: s.load,
          reps: s.reps,
          rpe: vm.draftRpe,
        }, true)
      },
    })
  }

  text({
    x: 50, y: 400, w: 366, h: 24,
    text: (s.completedSets || 0) + '/' + (s.totalSets || 0) + ' séries · ' +
      ((s.exerciseIndex || 0) + 1) + '/' + (s.exerciseCount || 0) + ' exercícios',
    text_size: 15,
    color: MUTED,
  })

  if (s.nextExerciseName) {
    text({
      x: 50, y: 424, w: 366, h: 23,
      text: 'A seguir: ' + s.nextExerciseName,
      text_size: 14,
      color: MUTED,
      text_style: text_style.ELLIPSIS,
    })
  }
}

function renderCardio(cardio) {
  text({
    x: 70, y: 105, w: 326, h: 42,
    text: 'PASSADEIRA',
    text_size: 22,
    color: MUTED,
  })

  text({
    x: 85, y: 150, w: 296, h: 70,
    text: fmt(cardio.speedKmh) + ' km/h',
    text_size: 44,
    color: ACCENT,
  })

  text({
    x: 60, y: 218, w: 346, h: 34,
    text: 'Alvo ' + fmt(cardio.targetSpeedKmh) + ' · ' +
      fmt(cardio.distanceKm) + ' km · ' + fmtTime(cardio.durationSec),
    text_size: 18,
    color: MUTED,
  })

  button({
    x: 66, y: 276, w: 136, h: 58,
    text: '− 0,5',
    click_func: () => sendAction('TREADMILL_SPEED_DELTA', { delta: -0.5 }),
  })
  button({
    x: 264, y: 276, w: 136, h: 58,
    text: '+ 0,5',
    click_func: () => sendAction('TREADMILL_SPEED_DELTA', { delta: 0.5 }),
  })

  button({
    x: 62, y: 350, w: 160, h: 58,
    text: cardio.paused ? 'Retomar' : 'Pausa',
    click_func: () => sendAction(cardio.paused ? 'TREADMILL_RESUME' : 'TREADMILL_PAUSE'),
  })
  button({
    x: 244, y: 350, w: 160, h: 58,
    text: 'STOP',
    normal_color: 0x6f1f1f,
    press_color: 0x8a2b2b,
    click_func: () => sendAction('TREADMILL_STOP', {}, true),
  })
}

function renderDaily(y) {
  if (!vm.daily) return
  const d = vm.daily
  const parts = []
  if (d.sleepScore !== null && d.sleepScore !== undefined) parts.push('Sono ' + d.sleepScore)
  if (d.restingHeartRate) parts.push('RHR ' + d.restingHeartRate)
  if (d.steps) parts.push(d.steps + ' passos')
  text({
    x: 55, y, w: 356, h: 60,
    text: parts.join(' · '),
    text_size: 18,
    color: MUTED,
    text_style: text_style.WRAP,
  })
}

function adoptState(data) {
  if (!data || data.ok === false) {
    vm.connected = false
    vm.error = data?.error || 'Training Hub indisponível'
    render()
    return
  }
  vm.connected = true
  vm.error = ''
  vm.workout = data
  if (!vm.heartRate && data.watchHeartRateBpm) vm.heartRate = data.watchHeartRateBpm
  stateReceivedAt = Date.now()
  if (vm.draftRpe === null || Number(data.setIndex) !== Number(vm._lastSetIndex) ||
      data.exerciseName !== vm._lastExercise) {
    vm.draftRpe = data.rpe === null || data.rpe === undefined ? 7 : Number(data.rpe)
  }
  vm._lastSetIndex = data.setIndex
  vm._lastExercise = data.exerciseName
  render()
}

function refreshState(manual) {
  if (!page) return
  page.request({ method: 'GET_STATE' })
    .then(adoptState)
    .catch((error) => {
      vm.connected = false
      vm.error = error?.message || 'Sem ligação ao Training Hub'
      if (manual) render()
    })
}

function sendAction(action, payload, vibrateOnSuccess) {
  if (!page) return
  vm.busy = true
  const params = { action, ...(payload || {}) }
  page.request({ method: 'ACTION', params })
    .then((data) => {
      vm.busy = false
      if (vibrateOnSuccess) safeVibrate(VIBRATOR_SCENE_NOTIFICATION)
      vm.draftRpe = null
      adoptState(data)
    })
    .catch((error) => {
      vm.busy = false
      vm.error = error?.message || 'Falha a enviar ação'
      render()
    })
}

function sendTelemetry(bpm) {
  const now = Date.now()
  if (!page || now - lastHrSentAt < 5000) return
  lastHrSentAt = now
  page.request({
    method: 'TELEMETRY',
    params: { heartRate: bpm, timestampMs: now },
  }).catch(() => {})
}

function collectDailyContext() {
  const daily = {
    date: localDateKey(),
    restingHeartRate: null,
    sleepScore: null,
    sleepMinutes: null,
    deepSleepMinutes: null,
    steps: null,
    stress: null,
    spo2Percent: null,
    skinTemperatureC: null,
  }

  try {
    const sensor = new HeartRate()
    const rhr = sensor.getResting()
    if (typeof rhr === 'number' && rhr > 0) daily.restingHeartRate = rhr
  } catch (_e) {}

  try {
    const info = new Sleep().getInfo()
    if (info) {
      if (typeof info.score === 'number' && info.score > 0) daily.sleepScore = info.score
      if (typeof info.totalTime === 'number' && info.totalTime > 0) daily.sleepMinutes = info.totalTime
      if (typeof info.deepTime === 'number' && info.deepTime >= 0) daily.deepSleepMinutes = info.deepTime
    }
  } catch (_e) {}

  try {
    const steps = new Step().getCurrent()
    if (typeof steps === 'number' && steps >= 0) daily.steps = steps
  } catch (_e) {}

  try {
    const stress = new Stress().getCurrent()
    if (stress && typeof stress.value === 'number' && stress.value > 0) daily.stress = stress.value
  } catch (_e) {}

  try {
    const spo2 = new BloodOxygen().getCurrent()
    if (spo2 && typeof spo2.value === 'number' && spo2.value > 0) daily.spo2Percent = spo2.value
  } catch (_e) {}

  try {
    const temp = new BodyTemperature().getCurrent()
    if (temp && typeof temp.current === 'number' && temp.current > 20 && temp.current < 45) {
      daily.skinTemperatureC = temp.current
    }
  } catch (_e) {}

  vm.daily = daily
  if (page) {
    page.request({ method: 'DAILY', params: daily }).catch(() => {})
  }
}

function startHeartRate() {
  try {
    hrSensor = new HeartRate()
    hrCallback = () => {
      if (!hrSensor) return
      const bpm = hrSensor.getCurrent()
      if (typeof bpm === 'number' && bpm > 20 && bpm < 250) {
        vm.heartRate = bpm
        sendTelemetry(bpm)
      }
    }
    hrSensor.onCurrentChange(hrCallback)
    hrCallback()
  } catch (_e) {}
}

function stopSensors() {
  try {
    if (hrSensor && hrCallback) hrSensor.offCurrentChange(hrCallback)
  } catch (_e) {}
  hrSensor = null
  hrCallback = null
}

Page(
  BasePage({
    onInit() {
      page = this
      try { setPageBrightTime({ brightTime: 120000 }) } catch (_e) {}
    },

    build() {
      render()
      startHeartRate()
      collectDailyContext()
      refreshState(true)
      pollTimer = setInterval(() => refreshState(false), 3000)
      tickTimer = setInterval(() => {
        if (!vm.connected) return
        const currentRest = restRemaining()
        if (lastRestValue > 0 && currentRest === 0) {
          safeVibrate(VIBRATOR_SCENE_NOTIFICATION)
          refreshState(false)
        } else if (currentRest > 0) {
          render()
        }
        lastRestValue = currentRest
      }, 1000)
    },

    onDestroy() {
      stopSensors()
      if (pollTimer) clearInterval(pollTimer)
      if (tickTimer) clearInterval(tickTimer)
      pollTimer = null
      tickTimer = null
      page = null
      try { resetPageBrightTime() } catch (_e) {}
      clearWidgets()
    },
  }),
)
