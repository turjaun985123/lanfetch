// Pure-logic tests for the Library derivations (FRONTEND.md
// §Testing): chip state, card badge, and the card accessible
// name — the computations every painted card and chip flows
// through.
import { describe, expect, it } from 'vitest';
import type { TitleDto } from './api';
import {
  applyTitleFilters,
  cardAriaLabel,
  cardBadge,
  chipActive,
  DEFAULT_FILTERS,
  filtersActive,
  titleMatchesFilters,
} from './library';

const movie: TitleDto = {
  folderUrl: 'http://srv/Movies/Film/',
  sourceName: 'DHAKA-FLIX-7',
  category: 'Movies',
  title: 'Film',
  year: 2024,
  kind: 'movie',
  posterUrl: null,
  language: 'EN',
  seasonCount: 0,
  bestQuality: '1080p',
  sourceTag: 'WEB-DL',
  maxSizeBytes: 4_500_000_000,
  hasDualAudio: false,
  modified: '2026-11-20 10:00',
  copies: 1,
  isFavorite: false,
};
const series: TitleDto = {
  ...movie,
  title: 'Show',
  kind: 'series',
  seasonCount: 3,
  bestQuality: '720p',
  sourceTag: 'BluRay',
  maxSizeBytes: 1_200_000_000,
  hasDualAudio: true,
};

describe('chipActive', () => {
  const idle = { activeQuery: '', favoritesActive: false, currentCategory: null };
  it('a category chip is active when it matches the current category', () => {
    expect(chipActive('cat', '', idle)).toBe(true);
    expect(chipActive('cat', 'Movies', { ...idle, currentCategory: 'Movies' })).toBe(true);
    expect(chipActive('cat', 'Movies', idle)).toBe(false);
  });
  it('the favorites chip follows favoritesActive', () => {
    expect(chipActive('fav', '', { ...idle, favoritesActive: true })).toBe(true);
    expect(chipActive('fav', '', idle)).toBe(false);
  });
  it('favorites suppress category chips', () => {
    expect(
      chipActive('cat', 'Movies', {
        activeQuery: '',
        favoritesActive: true,
        currentCategory: 'Movies',
      }),
    ).toBe(false);
  });
  it('an active search suppresses every chip (the Agent 18 fix)', () => {
    expect(chipActive('fav', '', { activeQuery: 'x', favoritesActive: true, currentCategory: null })).toBe(false);
    expect(chipActive('cat', '', { activeQuery: 'x', favoritesActive: false, currentCategory: null })).toBe(false);
  });
});

describe('cardBadge', () => {
  it('movies show their best quality', () => {
    expect(cardBadge(movie)).toBe('1080p');
    expect(cardBadge({ ...movie, bestQuality: null })).toBeNull();
  });
  it('series show season counts, singular as Series', () => {
    expect(cardBadge(series)).toBe('3 Seasons');
    expect(cardBadge({ ...series, seasonCount: 1 })).toBe('Series');
  });
});

describe('cardAriaLabel', () => {
  it('composes title, year, quality and favorite state', () => {
    expect(cardAriaLabel(movie)).toBe('Film, 2024, 1080p');
    expect(cardAriaLabel({ ...movie, isFavorite: true })).toBe(
      'Film, 2024, 1080p, favorited',
    );
  });
  it('series carry kind and season count', () => {
    expect(cardAriaLabel(series)).toBe('Show, 2024, series, 3 seasons, 720p');
    expect(cardAriaLabel({ ...series, seasonCount: 1, bestQuality: null })).toBe(
      'Show, 2024, series',
    );
  });
  it('yearless titles omit the year segment', () => {
    expect(cardAriaLabel({ ...movie, year: null })).toBe('Film, 1080p');
  });
});

describe('title filters (Android TitleFilterEngine parity)', () => {
  const oldBn: TitleDto = {
    ...movie,
    title: 'Purono Film',
    year: 1998,
    language: 'BN',
    bestQuality: '480p',
    sourceName: 'DHAKA-FLIX-9',
    sourceTag: null,
    maxSizeBytes: 650_000_000,
  };
  const noYear: TitleDto = {
    ...movie,
    title: 'Mystery',
    year: null,
    sourceTag: null,
    maxSizeBytes: -1,
  };
  const F = (p: Partial<typeof DEFAULT_FILTERS>) => ({
    ...DEFAULT_FILTERS,
    ...p,
  });

  it('the defaults match everything and count as inactive', () => {
    expect(filtersActive(DEFAULT_FILTERS)).toBe(false);
    expect(titleMatchesFilters(movie, DEFAULT_FILTERS)).toBe(true);
    expect(titleMatchesFilters(series, DEFAULT_FILTERS)).toBe(true);
    expect(titleMatchesFilters(noYear, DEFAULT_FILTERS)).toBe(true);
  });

  it('facet sets are OR within a dimension', () => {
    const kinds = F({ kinds: ['series'] });
    expect(filtersActive(kinds)).toBe(true);
    expect(titleMatchesFilters(series, kinds)).toBe(true);
    expect(titleMatchesFilters(movie, kinds)).toBe(false);
    const both = F({ kinds: ['movie', 'series'] });
    expect(titleMatchesFilters(movie, both)).toBe(true);
    expect(titleMatchesFilters(series, both)).toBe(true);
    const quals = F({ qualities: ['720p', '480p'] });
    expect(titleMatchesFilters(series, quals)).toBe(true);
    expect(titleMatchesFilters(oldBn, quals)).toBe(true);
    expect(titleMatchesFilters(movie, quals)).toBe(false);
    expect(
      titleMatchesFilters(
        { ...movie, bestQuality: null },
        F({ qualities: ['1080p'] }),
      ),
    ).toBe(false);
    expect(titleMatchesFilters(oldBn, F({ languages: ['BN'] }))).toBe(true);
    expect(titleMatchesFilters(movie, F({ languages: ['BN'] }))).toBe(false);
    expect(
      titleMatchesFilters(oldBn, F({ sources: ['DHAKA-FLIX-9'] })),
    ).toBe(true);
    expect(
      titleMatchesFilters(movie, F({ sources: ['DHAKA-FLIX-9'] })),
    ).toBe(false);
  });

  it('source tag facet matches the release tag; null never matches', () => {
    expect(titleMatchesFilters(movie, F({ sourceTags: ['WEB-DL'] }))).toBe(true);
    expect(titleMatchesFilters(series, F({ sourceTags: ['WEB-DL'] }))).toBe(false);
    expect(titleMatchesFilters(oldBn, F({ sourceTags: ['WEB-DL'] }))).toBe(false);
    expect(
      titleMatchesFilters(series, F({ sourceTags: ['WEB-DL', 'BluRay'] })),
    ).toBe(true);
  });

  it('dimensions combine with AND', () => {
    const f = F({ kinds: ['movie'], languages: ['BN'] });
    expect(titleMatchesFilters(oldBn, f)).toBe(true);
    expect(titleMatchesFilters(movie, f)).toBe(false);
    expect(titleMatchesFilters(series, f)).toBe(false);
  });

  it('year range is inclusive and excludes yearless titles', () => {
    const range = F({ yearFrom: 1990, yearTo: 2020 });
    expect(titleMatchesFilters(oldBn, range)).toBe(true); // 1998
    expect(titleMatchesFilters(movie, range)).toBe(false); // 2024
    expect(titleMatchesFilters(noYear, range)).toBe(false);
    expect(titleMatchesFilters(movie, F({ yearFrom: 2024 }))).toBe(true);
    expect(titleMatchesFilters(movie, F({ yearTo: 2023 }))).toBe(false);
    expect(titleMatchesFilters(noYear, F({ yearFrom: 1900 }))).toBe(false);
  });

  it('minimum size uses the largest file; unknown size fails', () => {
    const f = F({ minSizeBytes: 700_000_000 });
    expect(titleMatchesFilters(movie, f)).toBe(true); // 4.5 GB
    expect(titleMatchesFilters(series, f)).toBe(true); // 1.2 GB
    expect(titleMatchesFilters(oldBn, f)).toBe(false); // 650 MB
    expect(titleMatchesFilters(noYear, f)).toBe(false); // unknown (-1)
    expect(titleMatchesFilters(movie, F({ minSizeBytes: 4_000_000_000 }))).toBe(true);
    expect(titleMatchesFilters(series, F({ minSizeBytes: 4_000_000_000 }))).toBe(false);
  });

  it('dual audio only keeps dual-audio titles', () => {
    const f = F({ dualAudioOnly: true });
    expect(filtersActive(f)).toBe(true);
    expect(titleMatchesFilters(series, f)).toBe(true);
    expect(titleMatchesFilters(movie, f)).toBe(false);
  });

  it('applyTitleFilters sorts the Android way and never mutates', () => {
    const input = [series, oldBn, movie, noYear];
    const snapshot = [...input];
    // 'feed' keeps the crawl order untouched.
    expect(applyTitleFilters(input, DEFAULT_FILTERS)).toEqual(input);
    expect(
      applyTitleFilters(input, F({ sort: 'title' })).map((t) => t.title),
    ).toEqual(['Film', 'Mystery', 'Purono Film', 'Show']);
    // Year desc: 2024 ties break by title; yearless sinks last.
    expect(
      applyTitleFilters(input, F({ sort: 'year-desc' })).map((t) => t.title),
    ).toEqual(['Film', 'Show', 'Purono Film', 'Mystery']);
    // Size desc by largest file; unknown (-1) last.
    expect(
      applyTitleFilters(input, F({ sort: 'size-desc' })).map((t) => t.title),
    ).toEqual(['Film', 'Show', 'Purono Film', 'Mystery']);
    // Size asc: smallest first, unknown size still last.
    expect(
      applyTitleFilters(input, F({ sort: 'size-asc' })).map((t) => t.title),
    ).toEqual(['Purono Film', 'Show', 'Film', 'Mystery']);
    expect(
      applyTitleFilters(input, F({ kinds: ['movie'] })).map((t) => t.title),
    ).toEqual(['Purono Film', 'Film', 'Mystery']);
    expect(input).toEqual(snapshot);
  });
});
