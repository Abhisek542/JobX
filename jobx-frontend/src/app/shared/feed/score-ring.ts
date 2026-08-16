import { ChangeDetectionStrategy, Component, computed, input } from '@angular/core';

/**
 * The match-% ring from the mockup. Also used (with an explicit colour and
 * label) for the watchlist-health ring, so the two never drift apart.
 */
@Component({
  selector: 'app-score-ring',
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <div class="ring" [style.width.px]="size()" [style.height.px]="size()">
      <svg [attr.width]="size()" [attr.height]="size()" [attr.viewBox]="viewBox()">
        <circle
          [attr.cx]="center()"
          [attr.cy]="center()"
          [attr.r]="radius()"
          fill="none"
          stroke="var(--track)"
          stroke-width="6"
        />
        <circle
          class="fill"
          [attr.cx]="center()"
          [attr.cy]="center()"
          [attr.r]="radius()"
          fill="none"
          [attr.stroke]="color()"
          stroke-width="6"
          [attr.stroke-dasharray]="circumference()"
          [attr.stroke-dashoffset]="offset()"
        />
      </svg>
      <div class="pct" [style.font-size.px]="fontSize()">{{ label() }}</div>
    </div>
  `,
})
export class ScoreRing {
  /** 0–100. Drives both the arc and the default label. */
  readonly value = input.required<number>();
  readonly color = input('var(--brand)');
  readonly size = input(62);
  /** Overrides the "{value}%" default — the health ring shows "3/4". */
  readonly text = input<string | null>(null);

  protected readonly center = computed(() => this.size() / 2);
  protected readonly radius = computed(() => this.size() / 2 - 4);
  protected readonly viewBox = computed(() => `0 0 ${this.size()} ${this.size()}`);
  protected readonly circumference = computed(() => 2 * Math.PI * this.radius());
  protected readonly offset = computed(() =>
    (this.circumference() * (1 - clamp(this.value()) / 100)).toFixed(1),
  );
  protected readonly label = computed(() => this.text() ?? `${Math.round(this.value())}%`);
  protected readonly fontSize = computed(() => Math.max(12, Math.round(this.size() * 0.23)));
}

function clamp(value: number): number {
  if (!Number.isFinite(value)) return 0;
  return Math.min(100, Math.max(0, value));
}
