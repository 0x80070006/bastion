// SPDX-License-Identifier: GPL-3.0-or-later
// Typed wrappers around the backend commands. The webview never handles key material:
// it only receives views and sends user intents.
import { invoke } from "@tauri-apps/api/core";
import { listen, type UnlistenFn } from "@tauri-apps/api/event";

export type Connection = "idle" | "online" | "offline" | "pinMismatch";
export type PairingState = "pendingSas" | "awaitingPhone" | "active";

export interface AppStatus {
  vaultExists: boolean;
  unlocked: boolean;
  connection: Connection;
  productName: string;
  version: string;
  protocolVersion: number;
}

export interface LocationPoint {
  latitude: number;
  longitude: number;
  accuracyM: number;
  fixTimeMs: number;
  provider: string;
  speedMps: number | null;
}

export interface HealthView {
  feature: string;
  state: "active" | "disabled" | "degraded" | "unknown";
  reason: string;
}

export interface StatusView {
  batteryPercent: number | null;
  charging: boolean;
  network: string;
  trackingMode: string;
  lostMode: boolean;
  health: HealthView[];
  appVersion: string;
  receivedMs: number;
}

export interface DeviceSummary {
  id: string;
  label: string;
  state: PairingState;
  sas: string;
  fingerprint: string;
  pairedAtMs: number;
  lastSeenMs: number | null;
  lastLocation: LocationPoint | null;
  status: StatusView | null;
}

export interface CommandView {
  id: string;
  kind: string;
  sentMs: number;
  status: string;
  reason: string;
}

export interface DeviceDetail {
  summary: DeviceSummary;
  locations: LocationPoint[];
  commands: CommandView[];
}

export interface JournalEntry {
  timeMs: number;
  deviceId: string | null;
  deviceLabel: string | null;
  kind: string;
  detail: string | null;
}

export interface PairingView {
  qrDataUrl: string;
  uri: string;
  expiresAtMs: number;
  endpoint: string;
}

export interface SettingsView {
  autoLockMinutes: number;
  onlineMap: boolean;
  relayMode: "embedded" | "remote";
  relayEndpoint: string | null;
  advertisedHost: string | null;
  relayPin: string;
  controllerFingerprint: string;
}

export type RelaySetup =
  | { mode: "embedded"; port: number; advertisedHost: string | null }
  | { mode: "remote"; url: string; pinHex: string; adminToken: string };

export interface ContactInput {
  message: string;
  phone: string;
  email: string;
}

export type CameraChoice = "front" | "back" | "unspecified";

export type CommandRequest =
  | { kind: "ring"; durationSeconds: number; flashlight: boolean; vibrate: boolean }
  | { kind: "stopRing" }
  | { kind: "locate"; highAccuracy: boolean }
  | { kind: "trackingMode"; mode: "standby" | "active" }
  | { kind: "lostMode"; enabled: boolean; contact: ContactInput | null }
  | { kind: "status" }
  | { kind: "capturePhoto"; camera: CameraChoice }
  | {
      kind: "stream";
      enabled: boolean;
      camera: CameraChoice;
      fps: number;
      edgePx: number;
      durationSeconds: number;
    }
  | { kind: "audio"; enabled: boolean; durationSeconds: number }
  | {
      kind: "screen";
      enabled: boolean;
      fps: number;
      edgePx: number;
      durationSeconds: number;
      keepAwake: boolean;
    }
  | { kind: "intercom"; enabled: boolean; durationSeconds: number };

export type GlobalInputAction =
  "back" | "home" | "recents" | "notifications" | "quickSettings" | "wake" | "lock";

/** A remote-input action. Coordinates are normalised to 0..1 of the phone's display. */
export type RemoteInputRequest =
  | { kind: "tap"; x: number; y: number; longPress: boolean }
  | { kind: "swipe"; x1: number; y1: number; x2: number; y2: number; durationMs: number }
  | { kind: "text"; text: string; submit: boolean }
  | { kind: "global"; action: GlobalInputAction };

export interface PhotoView {
  id: string;
  camera: string;
  trigger: string;
  capturedMs: number;
  hasLocation: boolean;
}

export interface GeofenceView {
  id: string;
  name: string;
  latitude: number;
  longitude: number;
  radiusM: number;
}

/** A live stream frame pushed from the backend. */
export interface StreamFrame {
  deviceId: string;
  sequence: number;
  jpeg: string;
}

/** A live audio chunk pushed from the backend (base64 16-bit mono PCM). */
export interface AudioChunkMsg {
  deviceId: string;
  sequence: number;
  pcm: string;
  sampleRate: number;
}

/** A live screen-mirror frame pushed from the backend. */
export interface ScreenFrame {
  deviceId: string;
  sequence: number;
  jpeg: string;
  width: number;
  height: number;
  locked: boolean;
}

export type SensitiveRequest =
  | { kind: "lock"; contact: ContactInput | null }
  | { kind: "wipe"; includeExternalStorage: boolean }
  | { kind: "unpair" };

/** Backend error codes (see src-tauri/src/error.rs). */
export type ErrorCode =
  | "no_vault"
  | "vault_exists"
  | "locked"
  | "wrong_password"
  | "weak_password"
  | "throttled"
  | "vault_corrupted"
  | "invalid_input"
  | "unknown_device"
  | "not_active"
  | "relay_unreachable"
  | "relay_refused"
  | "relay_pin_mismatch"
  | "port_in_use"
  | "wipe_not_armed"
  | "storage"
  | "internal";

const KNOWN_ERRORS: readonly string[] = [
  "no_vault",
  "vault_exists",
  "locked",
  "wrong_password",
  "weak_password",
  "throttled",
  "vault_corrupted",
  "invalid_input",
  "unknown_device",
  "not_active",
  "relay_unreachable",
  "relay_refused",
  "relay_pin_mismatch",
  "port_in_use",
  "wipe_not_armed",
  "storage",
  "internal",
];

/** Normalizes anything thrown by `invoke` into a known error code. */
export function errorCode(error: unknown): ErrorCode {
  return typeof error === "string" && KNOWN_ERRORS.includes(error)
    ? (error as ErrorCode)
    : "internal";
}

export const api = {
  status: () => invoke<AppStatus>("app_status"),
  suggestedHost: () => invoke<string | null>("suggested_host"),
  setup: (password: string, relay: RelaySetup) => invoke<null>("setup", { password, relay }),
  unlock: (password: string) => invoke<null>("unlock", { password }),
  lock: () => invoke<null>("lock"),
  touch: () => invoke<null>("touch"),
  devices: () => invoke<DeviceSummary[]>("devices"),
  device: (id: string) => invoke<DeviceDetail>("device", { id }),
  journal: () => invoke<JournalEntry[]>("journal"),
  startPairing: () => invoke<PairingView>("start_pairing"),
  cancelPairing: () => invoke<null>("cancel_pairing"),
  confirmPairing: (id: string, accept: boolean) => invoke<null>("confirm_pairing", { id, accept }),
  sendCommand: (id: string, request: CommandRequest) =>
    invoke<null>("send_command", { id, request }),
  photos: (id: string) => invoke<PhotoView[]>("photos", { id }),
  photo: (photoId: string) => invoke<string>("photo", { photoId }),
  geofences: (id: string) => invoke<GeofenceView[]>("geofences", { id }),
  setGeofences: (id: string, zones: GeofenceView[]) => invoke<null>("set_geofences", { id, zones }),
  armWipe: (id: string) => invoke<number>("arm_wipe", { id }),
  disarmWipe: (id: string) => invoke<null>("disarm_wipe", { id }),
  sendSensitive: (id: string, password: string, request: SensitiveRequest) =>
    invoke<null>("send_sensitive", { id, password, request }),
  forgetDevice: (id: string, password: string) => invoke<null>("forget_device", { id, password }),
  remoteInput: (id: string, input: RemoteInputRequest) =>
    invoke<null>("remote_input", { id, input }),
  audioPlay: (id: string, sequence: number, pcm: string, sampleRate: number) =>
    invoke<null>("audio_play", { id, sequence, pcm, sampleRate }),
  settings: () => invoke<SettingsView>("settings"),
  updateSettings: (update: {
    autoLockMinutes: number;
    onlineMap: boolean;
    advertisedHost: string | null;
  }) => invoke<null>("update_settings", { update }),
};

export type BackendEvent = "changed" | "attention" | "locked";

/** Subscribes to backend push events. */
export async function onBackend(event: BackendEvent, handler: () => void): Promise<UnlistenFn> {
  return listen(`bastion://${event}`, handler);
}

/** Subscribes to live stream frames. */
export async function onFrame(handler: (frame: StreamFrame) => void): Promise<UnlistenFn> {
  return listen<StreamFrame>("bastion://frame", (event) => handler(event.payload));
}

/** Subscribes to live audio chunks. */
export async function onAudio(handler: (chunk: AudioChunkMsg) => void): Promise<UnlistenFn> {
  return listen<AudioChunkMsg>("bastion://audio", (event) => handler(event.payload));
}

/** Subscribes to live screen-mirror frames. */
export async function onScreen(handler: (frame: ScreenFrame) => void): Promise<UnlistenFn> {
  return listen<ScreenFrame>("bastion://screen", (event) => handler(event.payload));
}
