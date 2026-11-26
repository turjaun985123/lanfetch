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
