// Typed Tauri invoke wrappers — the only place command names appear
// in the frontend. Types mirror src-tauri/src/dto.rs and are pinned
// by the golden-fixture contract tests (QA plan).
import { invoke } from '@tauri-apps/api/core';

export interface SettingsDto {
  connections: number;
  maxConcurrent: number;
  theme: string;
  language: string;
  onboardingDone: boolean;
}

export interface SourceDto {
  name: string;
  rootUrl: string;
  enabled: boolean;
  custom: boolean;
}

export interface SegmentDto {
  start: number;
  end: number | null;
  done: number;
}

export interface TaskDto {
  id: number;
  url: string;
  name: string;
  state: 'queued' | 'running' | 'paused' | 'completed' | 'failed';
  total: number;
  downloaded: number;
  fraction: number;
  speedBps: number;
  etaSecs: number | null;
  error: string | null;
  ready: boolean;
  segments: SegmentDto[];
}

export interface DownloadListDto {
  stamp: number;
  tasks: TaskDto[];
  activeCount: number;
}

export interface BootstrapDto {
  appVersion: string;
  settings: SettingsDto;
  sources: SourceDto[];
  favorites: string[];
  downloads: DownloadListDto;
  downloadsDir: string;
  dataDir: string;
}

export interface SourceProbeDto {
  name: string;
  rootUrl: string;
  status: 'up' | 'down' | 'disabled';
  latencyMs: number | null;
}

export function getBootstrap(): Promise<BootstrapDto> {
  return invoke<BootstrapDto>('get_bootstrap');
}

export function probeSources(): Promise<SourceProbeDto[]> {
  return invoke<SourceProbeDto[]>('probe_sources');
}
