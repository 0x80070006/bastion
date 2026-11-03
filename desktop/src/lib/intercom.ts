// SPDX-License-Identifier: GPL-3.0-or-later
// Two-way voice between the PC and a phone: the phone's microphone plays on the PC speakers and
// the PC microphone plays on the phone. Used for "sound on the PC" during a call, where the phone
// is on speaker and its microphone picks the caller up (Android gives apps no direct call audio).
import type { UnlistenFn } from "@tauri-apps/api/event";
import { api, awaitCommand, onAudio } from "./api";

const INTERCOM_SECONDS = 900;
const BUFFER_SAMPLES = 4096;
// Android silences third-party microphones during a phone call. Chunks that keep arriving but stay
// at digital silence for this long mean exactly that, not a quiet room.
const SILENCE_AFTER_MS = 5000;
const SILENCE_PEAK = 40;
const MIN_CHUNKS = 5;

function encodePcm(samples: Float32Array): string {
  const pcm = new Int16Array(samples.length);
  for (let i = 0; i < samples.length; i++) {
    const s = Math.max(-1, Math.min(1, samples[i] ?? 0));
    pcm[i] = s < 0 ? s * 0x8000 : s * 0x7fff;
  }
  const bytes = new Uint8Array(pcm.buffer);
  let binary = "";
  for (let i = 0; i < bytes.length; i++) binary += String.fromCharCode(bytes[i] ?? 0);
  return btoa(binary);
}

/** Why the intercom could not open: a PC-side code, or the reason the phone gave. */
export type IntercomFailure = string;

export type IntercomResult = { ok: true } | { ok: false; reason: IntercomFailure };

export interface Intercom {
  /** True while the intercom is open. */
  readonly running: boolean;
  /** Prepares audio on a user gesture (browser autoplay rules). Call from a click handler. */
  prepare(): Promise<void>;
  /** Opens the intercom and waits for the phone to confirm it. */
  start(): Promise<IntercomResult>;
  stop(): Promise<void>;
}

export function createIntercom(getDeviceId: () => string, onSilence?: () => void): Intercom {
  let running = false;
  let audioCtx: AudioContext | undefined;
  let micCtx: AudioContext | undefined;
  let micStream: MediaStream | undefined;
  let micNode: ScriptProcessorNode | undefined;
  let micSource: MediaStreamAudioSourceNode | undefined;
  let unlisten: UnlistenFn | undefined;
  let nextPlayTime = 0;
  let outSeq = 0;
  let chunks = 0;
  let peak = 0;
  let firstChunkAt = 0;
  let silenceReported = false;

  function track(samples: Int16Array) {
    if (chunks === 0) firstChunkAt = Date.now();
    chunks += 1;
    for (let i = 0; i < samples.length; i++) {
      const magnitude = Math.abs(samples[i] ?? 0);
      if (magnitude > peak) peak = magnitude;
    }
    if (
      !silenceReported &&
      chunks >= MIN_CHUNKS &&
      peak < SILENCE_PEAK &&
      Date.now() - firstChunkAt >= SILENCE_AFTER_MS
    ) {
      silenceReported = true;
      onSilence?.();
    }
  }

  function playChunk(base64: string, sampleRate: number) {
    const ctx = audioCtx ?? (audioCtx = new AudioContext());
    const bytes = Uint8Array.from(atob(base64), (c) => c.charCodeAt(0));
    const samples = new Int16Array(
      bytes.buffer,
      bytes.byteOffset,
      Math.floor(bytes.byteLength / 2),
    );
    if (samples.length === 0) return;
    track(samples);
    const buffer = ctx.createBuffer(1, samples.length, sampleRate);
    const channel = buffer.getChannelData(0);
    for (let i = 0; i < samples.length; i++) channel[i] = (samples[i] ?? 0) / 0x8000;
    const source = ctx.createBufferSource();
    source.buffer = buffer;
    source.connect(ctx.destination);
    const start = Math.max(nextPlayTime, ctx.currentTime);
    source.start(start);
    nextPlayTime = start + buffer.duration;
  }

  function stopMic() {
    micNode?.disconnect();
    micSource?.disconnect();
    micStream?.getTracks().forEach((mediaTrack) => mediaTrack.stop());
    void micCtx?.close();
    micNode = undefined;
    micSource = undefined;
    micStream = undefined;
    micCtx = undefined;
  }

  async function openMic(): Promise<IntercomFailure | undefined> {
    if (!navigator.mediaDevices?.getUserMedia) return "pc_mic_missing";
    try {
      micStream = await navigator.mediaDevices.getUserMedia({
        audio: { echoCancellation: true, noiseSuppression: true },
      });
    } catch (error) {
      const name = error instanceof DOMException ? error.name : "";
      return name === "NotFoundError" || name === "OverconstrainedError"
        ? "pc_mic_missing"
        : "pc_mic_denied";
    }
    try {
      micCtx = new AudioContext();
      await micCtx.resume().catch(() => undefined);
      micSource = micCtx.createMediaStreamSource(micStream);
      micNode = micCtx.createScriptProcessor(BUFFER_SAMPLES, 1, 1);
      const rate = micCtx.sampleRate;
      micNode.onaudioprocess = (event) => {
        if (!running) return;
        const input = event.inputBuffer.getChannelData(0);
        void api.audioPlay(getDeviceId(), outSeq++, encodePcm(input), rate).catch(() => undefined);
      };
      micSource.connect(micNode);
      micNode.connect(micCtx.destination);
    } catch {
      stopMic();
      return "pc_audio_error";
    }
    return undefined;
  }

  async function stop() {
    const wasRunning = running;
    running = false;
    stopMic();
    unlisten?.();
    unlisten = undefined;
    nextPlayTime = 0;
    await audioCtx?.close().catch(() => undefined);
    audioCtx = undefined;
    if (wasRunning) {
      await api
        .sendCommand(getDeviceId(), { kind: "intercom", enabled: false, durationSeconds: 1 })
        .catch(() => undefined);
    }
  }

  return {
    get running() {
      return running;
    },
    async prepare() {
      await (audioCtx ?? (audioCtx = new AudioContext())).resume().catch(() => undefined);
    },
    async start() {
      if (running) return { ok: true };
      const micFailure = await openMic();
      if (micFailure) return { ok: false, reason: micFailure };
      nextPlayTime = 0;
      outSeq = 0;
      chunks = 0;
      peak = 0;
      silenceReported = false;
      unlisten = await onAudio((chunk) => {
        if (running && chunk.deviceId === getDeviceId()) playChunk(chunk.pcm, chunk.sampleRate);
      });
      const sentAt = Date.now();
      running = true;
      try {
        await api.sendCommand(getDeviceId(), {
          kind: "intercom",
          enabled: true,
          durationSeconds: INTERCOM_SECONDS,
        });
      } catch {
        await stop();
        return { ok: false, reason: "send_failed" };
      }
      // The phone answers asynchronously: a refused microphone must surface here, not as silence.
      const outcome = await awaitCommand(getDeviceId(), "intercom", sentAt, 10_000);
      const refused = ["failed", "rejected", "unsupported", "timeout"].includes(outcome.status);
      if (refused) {
        await stop();
        return { ok: false, reason: outcome.reason || outcome.status };
      }
      return { ok: true };
    },
    stop,
  };
}
