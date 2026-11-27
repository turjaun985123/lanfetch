// Pure-logic tests for the Library derivations (FRONTEND.md
// §Testing): chip state, card badge, and the card accessible
// name — the computations every painted card and chip flows
// through.
import { describe, expect, it } from 'vitest';
import type { TitleDto } from './api';
import { cardAriaLabel, cardBadge, chipActive } from './library';

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
