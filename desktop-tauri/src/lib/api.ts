// Typed command wrappers — the only place command names appear in
// the frontend. Types mirror engine/src/dto.rs and are pinned
// by the golden-fixture contract tests (QA plan).
//
// Transport is chosen once at runtime: inside the desktop app the
// calls go over Tauri IPC; in a browser (web mode, `lanfetch
// --serve`) the identical commands are POSTed to the server's
// /api/* endpoints, and the downloads-changed event arrives over
// SSE instead of a Tauri event. Nothing else in the UI knows or
// cares which transport is live.
import { invoke as tauriInvoke } from '@tauri-apps/api/core';
import { listen } from '@tauri-apps/api/event';

const isTauri =
  typeof window !== 'undefined' && '__TAURI_INTERNALS__' in window;

async function invoke<T>(
  cmd: string,
  args?: Record<string, unknown>,
): Promise<T> {
  if (isTauri) return tauriInvoke<T>(cmd, args);
  const res = await fetch('/api/' + cmd, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(args ?? {}),
  });
  const data: unknown = await res.json().catch(() => null);
  if (!res.ok) {
    const message =
      data && typeof data === 'object' && 'error' in data
        ? String((data as { error: unknown }).error)
        : 'request failed: ' + res.status;
    throw new Error(message);
  }
  return data as T;
}

/** Subscribe to the manager's downloads-changed stamp, over
 * whichever transport is live. */
export function onDownloadsChanged(cb: (stamp: number) => void): void {
  if (isTauri) {
    void listen<number>('downloads-changed', (e) => cb(e.payload));
    return;
  }
  const source = new EventSource('/api/events');
  source.onmessage = (e) => {
    const stamp = Number(e.data);
    if (Number.isFinite(stamp)) cb(stamp);
  };
}

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
  /** Completed, but the file is gone from the downloads folder. */
  fileMissing: boolean;
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

/** Register remote poster URLs; returns `lanfetch-poster://` srcs
 * aligned with the input. Bytes travel via the protocol handler,
 * never through IPC. */
export function posterSrcs(urls: string[]): Promise<string[]> {
  return invoke<string[]>('poster_srcs', { urls });
}

export interface CategoryDto {
  name: string;
  sources: string[];
}

export interface TitleDto {
  folderUrl: string;
  sourceName: string;
  category: string;
  title: string;
  year: number | null;
  kind: string; // "movie" | "series"
  posterUrl: string | null;
  language: string | null;
  seasonCount: number;
  bestQuality: string | null;
  copies: number;
  isFavorite: boolean;
}

export interface FeedPageDto {
  generation: number;
  titles: TitleDto[];
  hasMore: boolean;
  /** True when this page belongs to a feed that was already
   * replaced — drop it, never render it. */
  superseded: boolean;
}

export function getCategories(refresh: boolean): Promise<CategoryDto[]> {
  return invoke<CategoryDto[]>('get_categories', { refresh });
}

export function feedOpen(category: string | null): Promise<FeedPageDto> {
  return invoke<FeedPageDto>('feed_open', { category });
}

export function feedNext(generation: number): Promise<FeedPageDto> {
  return invoke<FeedPageDto>('feed_next', { generation });
}

export interface FileDto {
  fileName: string;
  fileUrl: string;
  size: number; // -1 = unknown
  quality: string | null;
  sourceTag: string | null;
  codec: string | null;
  audio: string | null;
  season: number | null;
  episode: number | null;
  downloadState:
    | 'queued'
    | 'running'
    | 'paused'
    | 'completed'
    | 'failed'
    | null;
  downloadFraction: number | null;
}

export interface TitleDetailDto {
  title: TitleDto;
  files: FileDto[];
}

export function getTitleDetail(folderUrl: string): Promise<TitleDetailDto> {
  return invoke<TitleDetailDto>('get_title_detail', { folderUrl });
}

export function startDownload(
  url: string,
  name: string,
): Promise<DownloadListDto> {
  return invoke<DownloadListDto>('start_download', { url, name });
}

export function toggleFavorite(folderUrl: string): Promise<boolean> {
  return invoke<boolean>('toggle_favorite', { folderUrl });
}

export function getDownloads(): Promise<DownloadListDto> {
  return invoke<DownloadListDto>('get_downloads');
}

export function pauseDownload(id: number): Promise<DownloadListDto> {
  return invoke<DownloadListDto>('pause_download', { id });
}

export function resumeDownload(id: number): Promise<DownloadListDto> {
  return invoke<DownloadListDto>('resume_download', { id });
}

export function removeDownload(id: number): Promise<DownloadListDto> {
  return invoke<DownloadListDto>('remove_download', { id });
}

export function clearCompletedDownloads(): Promise<DownloadListDto> {
  return invoke<DownloadListDto>('clear_completed_downloads');
}

export function searchTitles(query: string): Promise<TitleDto[]> {
  return invoke<TitleDto[]>('search_titles', { query });
}

export function updateSettings(s: SettingsDto): Promise<SettingsDto> {
  return invoke<SettingsDto>('update_settings', {
    connections: s.connections,
    maxConcurrent: s.maxConcurrent,
    theme: s.theme,
    language: s.language,
    onboardingDone: s.onboardingDone,
  });
}

export function addSource(
  name: string,
  rootUrl: string,
): Promise<SourceDto[]> {
  return invoke<SourceDto[]>('add_source', { name, rootUrl });
}

export function removeSource(name: string): Promise<SourceDto[]> {
  return invoke<SourceDto[]>('remove_source', { name });
}

export function setSourceEnabled(
  name: string,
  enabled: boolean,
): Promise<SourceDto[]> {
  return invoke<SourceDto[]>('set_source_enabled', { name, enabled });
}

export function getFavorites(): Promise<TitleDto[]> {
  return invoke<TitleDto[]>('get_favorites');
}

export function pauseAllDownloads(): Promise<DownloadListDto> {
  return invoke<DownloadListDto>('pause_all_downloads');
}

export function resumeAllDownloads(): Promise<DownloadListDto> {
  return invoke<DownloadListDto>('resume_all_downloads');
}

export function redownloadDownload(id: number): Promise<DownloadListDto> {
  return invoke<DownloadListDto>('redownload_download', { id });
}

/** Newer desktop version string, or null when up to date / offline. */
export function checkUpdate(): Promise<string | null> {
  return invoke<string | null>('check_update');
}
