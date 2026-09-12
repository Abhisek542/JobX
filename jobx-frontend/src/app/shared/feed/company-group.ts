import { ChangeDetectionStrategy, Component, computed, inject, input, output } from '@angular/core';
import { WatchlistStore } from '../../core/services/watchlist.store';
import { companyStatusLine } from '../../core/util/watchlist-status';
import { FeedStore } from '../../features/dashboard/feed.store';
import { CompanyGroup, band } from '../../features/dashboard/feed-logic';
import { CompanyLogo } from '../ui/company-logo';
import { Icon } from '../ui/icon';
import { MatchCard } from './match-card';

/**
 * One company's roles behind a collapsible header.
 *
 * This replaces the old "Sort by: Company", which only interleaved cards
 * alphabetically: it clustered a company's roles without ever separating them,
 * let pagination split a company across a page boundary, and still could not
 * answer "show me only this company". A header you can collapse answers all
 * three.
 *
 * HONESTY (uiux_plan.md §7): every number here is derived from rows already on
 * screen. The counts describe the group *within the current filtered view* —
 * the header says "in this view" when a filter is active rather than implying
 * it is the company's whole board — and the health line is the real
 * lastFetchStatus, so a company with no new roles can show why.
 */
@Component({
  selector: 'app-company-group',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [CompanyLogo, Icon, MatchCard],
  template: `
    <section class="cgroup" [class.is-collapsed]="collapsed()">
      <h3 class="cgroup-h">
        <button
          type="button"
          class="cgroup-btn"
          [attr.aria-expanded]="!collapsed()"
          [attr.aria-controls]="collapsed() ? null : panelId()"
          (click)="feed.toggleCollapsed(group().companyId)"
        >
          <app-icon name="chevron-down" size="sm" />
          <app-company-logo [companyName]="group().companyName" [size]="32" />

          <span class="cgroup-id">
            <span class="nm">{{ group().companyName }}</span>
            <span class="sub">
              <span>{{ roleCount() }}</span>
              @if (group().newCount > 0) {
                <span class="dot"></span>
                <span class="fresh">{{ group().newCount }} new</span>
              }
              @if (health(); as line) {
                <span class="dot"></span>
                <span [class.bad]="line.bad">{{ line.text }}</span>
              }
            </span>
          </span>

          <span class="cgroup-best" [style.color]="bestColor()">
            {{ group().bestScore }}%
            <span class="lbl">best</span>
          </span>
        </button>
      </h3>

      @if (!collapsed()) {
        <div class="cgroup-body" [id]="panelId()" role="region" [attr.aria-label]="group().companyName">
          <div class="cards" [class.cards-compact]="compact()">
            @for (match of group().matches; track match.id; let i = $index) {
              <app-match-card
                [match]="match"
                [index]="i"
                [compact]="compact()"
                [pending]="feed.pending().has(match.id)"
                (statusChange)="feed.setStatus(match.id, $event)"
                (openDetails)="openDetails.emit(match.id)"
              />
            }
          </div>
        </div>
      }
    </section>
  `,
})
export class CompanyGroupSection {
  readonly group = input.required<CompanyGroup>();
  /** Render the one-line archive rows — the Dismissed pill is active. */
  readonly compact = input(false);

  /** The drawer is owned by MatchFeed, so the id goes back up to it. */
  readonly openDetails = output<string>();

  protected readonly feed = inject(FeedStore);
  private readonly watchlist = inject(WatchlistStore);

  protected readonly collapsed = computed(() => this.feed.collapsed().has(this.group().companyId));
  protected readonly panelId = computed(() => `cgroup-${this.group().companyId}`);
  /**
   * Same rule the cards follow: an archived group stops claiming match quality
   * in colour. A green "92% best" over roles the user already rejected is a
   * claim the view is no longer making (see match-card.ts ringColor).
   */
  protected readonly bestColor = computed(() =>
    this.compact() ? 'var(--faint)' : band(this.group().bestScore).color,
  );

  /**
   * Says "in this view" whenever a filter is narrowing the feed, so the number
   * is never read as the company's full board.
   */
  protected readonly roleCount = computed(() => {
    const n = this.group().count;
    const noun = `${n} role${n === 1 ? '' : 's'}`;
    return this.feed.isFiltered() ? `${noun} in this view` : noun;
  });

  /** Null when the user no longer watches this board — then we say nothing. */
  protected readonly health = computed(() => {
    const watched = this.watchlist.byCompanyId().get(this.group().companyId);
    return watched ? companyStatusLine(watched) : null;
  });
}
