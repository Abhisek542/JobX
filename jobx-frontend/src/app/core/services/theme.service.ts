import { Injectable, computed, signal } from '@angular/core';
import { readStorage, writeStorage } from '../util/storage';

export type Theme = 'light' | 'dark';

const THEME_KEY = 'jobx-theme';

/**
 * Light + dark, toggled from the action bar, persisted per device
 * (uiux_plan.md §3). No backend field is needed — and the choice defaults to
 * the OS preference until the user expresses one.
 */
@Injectable({ providedIn: 'root' })
export class ThemeService {
  private readonly current = signal<Theme>(initialTheme());

  readonly theme = this.current.asReadonly();
  readonly isDark = computed(() => this.current() === 'dark');

  constructor() {
    this.apply(this.current());
  }

  toggle(): void {
    this.set(this.current() === 'dark' ? 'light' : 'dark');
  }

  set(theme: Theme): void {
    this.current.set(theme);
    this.apply(theme);
    writeStorage(THEME_KEY, theme);
  }

  private apply(theme: Theme): void {
    document.documentElement.dataset['theme'] = theme;
  }
}

function initialTheme(): Theme {
  const stored = readStorage(THEME_KEY);
  if (stored === 'light' || stored === 'dark') return stored;
  return matchMedia('(prefers-color-scheme: dark)').matches ? 'dark' : 'light';
}
