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
  copies: 1,
  isFavorite: false,
};
const series: TitleDto = {
  ...movie,
  title: 'Show',
  kind: 'series',
  seasonCount: 3,
  bestQuality: '720p',
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

describe('title filters', () => {
  const oldBn: TitleDto = {
    ...movie,
    title: 'Purono Film',
    year: 1998,
    language: 'BN',
    bestQuality: '480p',
    sourceName: 'DHAKA-FLIX-9',
  };
  const noYear: TitleDto = { ...movie, title: 'Mystery', year: null };

  it('the defaults match everything and count as inactive', () => {
    expect(filtersActive(DEFAULT_FILTERS)).toBe(false);
    expect(titleMatchesFilters(movie, DEFAULT_FILTERS)).toBe(true);
    expect(titleMatchesFilters(series, DEFAULT_FILTERS)).toBe(true);
    expect(titleMatchesFilters(noYear, DEFAULT_FILTERS)).toBe(true);
  });

  it('kind, quality and language match exactly', () => {
    const f = { ...DEFAULT_FILTERS, kind: 'series' };
    expect(filtersActive(f)).toBe(true);
    expect(titleMatchesFilters(series, f)).toBe(true);
    expect(titleMatchesFilters(movie, f)).toBe(false);
    expect(
      titleMatchesFilters(movie, { ...DEFAULT_FILTERS, quality: '720p' }),
    ).toBe(false);
    expect(
      titleMatchesFilters(oldBn, { ...DEFAULT_FILTERS, language: 'BN' }),
    ).toBe(true);
    expect(
      titleMatchesFilters(movie, { ...DEFAULT_FILTERS, language: 'BN' }),
    ).toBe(false);
  });

  it('minimum quality uses the ladder, not string order', () => {
    const hd = { ...DEFAULT_FILTERS, quality: 'min:720p' };
    expect(titleMatchesFilters(movie, hd)).toBe(true); // 1080p
    expect(titleMatchesFilters(series, hd)).toBe(true); // 720p
    expect(titleMatchesFilters(oldBn, hd)).toBe(false); // 480p
    expect(
      titleMatchesFilters(series, {
        ...DEFAULT_FILTERS,
        quality: 'min:1080p',
      }),
    ).toBe(false);
    expect(
      titleMatchesFilters(
        { ...movie, bestQuality: null },
        { ...DEFAULT_FILTERS, quality: 'min:720p' },
      ),
    ).toBe(false);
  });

  it('source matches the server name exactly', () => {
    const f = { ...DEFAULT_FILTERS, source: 'DHAKA-FLIX-9' };
    expect(titleMatchesFilters(oldBn, f)).toBe(true);
    expect(titleMatchesFilters(movie, f)).toBe(false);
  });

  it('year range is inclusive and excludes yearless titles', () => {
    const range = { ...DEFAULT_FILTERS, yearFrom: '1990', yearTo: '2020' };
    expect(titleMatchesFilters(oldBn, range)).toBe(true); // 1998
    expect(titleMatchesFilters(movie, range)).toBe(false); // 2024
    expect(titleMatchesFilters(noYear, range)).toBe(false);
    expect(
      titleMatchesFilters(movie, { ...DEFAULT_FILTERS, yearFrom: '2024' }),
    ).toBe(true);
    expect(
      titleMatchesFilters(movie, { ...DEFAULT_FILTERS, yearTo: '2023' }),
    ).toBe(false);
  });

  it('applyTitleFilters filters, sorts and never mutates its input', () => {
    const input = [series, oldBn, movie];
    const snapshot = [...input];
    expect(applyTitleFilters(input, DEFAULT_FILTERS)).toEqual(input);
    expect(
      applyTitleFilters(input, { ...DEFAULT_FILTERS, sort: 'az' }).map(
        (t) => t.title,
      ),
    ).toEqual(['Film', 'Purono Film', 'Show']);
    expect(
      applyTitleFilters(input, {
        ...DEFAULT_FILTERS,
        sort: 'year-desc',
      }).map((t) => t.title),
    ).toEqual(['Show', 'Film', 'Purono Film']);
    expect(
      applyTitleFilters(input, {
        ...DEFAULT_FILTERS,
        sort: 'quality',
      }).map((t) => t.title),
    ).toEqual(['Film', 'Show', 'Purono Film']);
    expect(
      applyTitleFilters(input, { ...DEFAULT_FILTERS, kind: 'movie' }).map(
        (t) => t.title,
      ),
    ).toEqual(['Purono Film', 'Film']);
    expect(input).toEqual(snapshot);
  });
});
