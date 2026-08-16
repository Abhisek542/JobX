import { Injectable, signal } from '@angular/core';
import { readStorage, writeStorage } from '../util/storage';

const COLLAPSED_KEY = 'jobx-nav-collapsed';

/**
 * Chrome-level UI state: which global overlay is open, and the sidebar's
 * collapsed/mobile state. Lives here so the sidebar, the action bar and the
 * rail panels can all raise the same two modals without prop-drilling.
 */
@Injectable({ providedIn: 'root' })
export class UiStore {
  readonly addCompanyOpen = signal(false);
  readonly preferencesOpen = signal(false);
  readonly navCollapsed = signal(readStorage(COLLAPSED_KEY) === '1');
  readonly mobileNavOpen = signal(false);

  openAddCompany(): void {
    this.mobileNavOpen.set(false);
    this.addCompanyOpen.set(true);
  }

  closeAddCompany(): void {
    this.addCompanyOpen.set(false);
  }

  openPreferences(): void {
    this.mobileNavOpen.set(false);
    this.preferencesOpen.set(true);
  }

  closePreferences(): void {
    this.preferencesOpen.set(false);
  }

  toggleNavCollapsed(): void {
    const next = !this.navCollapsed();
    this.navCollapsed.set(next);
    writeStorage(COLLAPSED_KEY, next ? '1' : '0');
  }

  toggleMobileNav(): void {
    this.mobileNavOpen.update((open) => !open);
  }

  closeMobileNav(): void {
    this.mobileNavOpen.set(false);
  }
}
