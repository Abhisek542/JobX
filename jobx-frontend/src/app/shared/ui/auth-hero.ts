import { ChangeDetectionStrategy, Component, inject, input } from '@angular/core';
import { RouterLink } from '@angular/router';
import { ThemeService } from '../../core/services/theme.service';
import { Icon } from './icon';

/**
 * The landing hero both auth pages sit in (docs/newui-dark-redesign-mockup.html,
 * modelled on docs/newUi/newUi.png): nav · headline · the page's own form ·
 * supported-platform chips · city illustration · a row of product facts.
 *
 * Purely presentational. The page projects its headline ([heroTitle]), lead
 * ([heroLead]), form (default slot) and footer line ([heroFoot]); all form
 * state and submission stay in LoginPage / RegisterPage.
 *
 * HONESTY (uiux_plan.md §7): the stats are product facts — five supported ATS
 * platforms, the 30-minute poll, the six-day TTL — never user counts or ratings.
 */
@Component({
  selector: 'app-auth-hero',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [Icon, RouterLink],
  template: `
    <div class="landing">
      <div class="hero">
        <nav class="hero-nav" aria-label="Jobx">
          <div class="brand"><span>Job<span class="x">X</span></span></div>
          <div class="links">
            <button type="button" (click)="scrollTo('facts')">How it works</button>
            <button type="button" (click)="scrollTo('facts')">Supported boards</button>
          </div>
          <span class="sp"></span>
          <div class="right">
            <button
              class="btn btn-ghost btn-icon"
              type="button"
              [attr.aria-label]="theme.isDark() ? 'Switch to light theme' : 'Switch to dark theme'"
              (click)="theme.toggle()"
            >
              <app-icon [name]="theme.isDark() ? 'sun' : 'moon'" size="sm" />
            </button>
            @if (mode() === 'login') {
              <button class="login" type="button" (click)="focusForm()">Login</button>
              <a class="btn btn-primary" routerLink="/register">
                Get Started
                <app-icon name="arrow-right" size="sm" />
              </a>
            } @else {
              <a class="login" routerLink="/login">Login</a>
              <button class="btn btn-primary" type="button" (click)="focusForm()">
                Get Started
                <app-icon name="arrow-right" size="sm" />
              </button>
            }
          </div>
        </nav>

        <div class="hero-body">
          <div class="hero-copy">
            <span class="eyebrow">Your career companion</span>
            <ng-content select="[heroTitle]" />
            <ng-content select="[heroLead]" />
            <ng-content />
            <div class="popular">
              <span>Works with:</span>
              @for (platform of platforms; track platform) {
                <span class="pchip">{{ platform }}</span>
              }
            </div>
            <ng-content select="[heroFoot]" />
          </div>

          <div class="hero-art" aria-hidden="true">
            <svg viewBox="0 0 800 700" preserveAspectRatio="xMidYMax slice">
              <defs>
                <linearGradient id="h-sky" x1="0" y1="0" x2="0" y2="1"><stop offset="0" stop-color="#0d0a26" /><stop offset=".45" stop-color="#241665" /><stop offset=".8" stop-color="#1a1150" /><stop offset="1" stop-color="#0c0a24" /></linearGradient>
                <radialGradient id="h-glow" cx=".68" cy=".38" r=".45"><stop offset="0" stop-color="#9f7aff" stop-opacity=".55" /><stop offset=".5" stop-color="#6d3df5" stop-opacity=".18" /><stop offset="1" stop-color="#6d3df5" stop-opacity="0" /></radialGradient>
                <linearGradient id="h-far" x1="0" y1="0" x2="0" y2="1"><stop offset="0" stop-color="#2c2173" /><stop offset="1" stop-color="#150f3a" /></linearGradient>
                <linearGradient id="h-mid" x1="0" y1="0" x2="0" y2="1"><stop offset="0" stop-color="#1f1757" /><stop offset="1" stop-color="#0d0a27" /></linearGradient>
                <linearGradient id="h-tower" x1="0" y1="0" x2="1" y2="0"><stop offset="0" stop-color="#8456ff" /><stop offset=".6" stop-color="#5a2fd8" /><stop offset="1" stop-color="#3a1f9a" /></linearGradient>
                <linearGradient id="h-road" x1="0" y1="1" x2="0" y2="0"><stop offset="0" stop-color="#c9b6ff" /><stop offset=".6" stop-color="#9a78ff" /><stop offset="1" stop-color="#7c4dff" stop-opacity=".6" /></linearGradient>
                <linearGradient id="h-jacket" x1="0" y1="0" x2="1" y2="1"><stop offset="0" stop-color="#3d3596" /><stop offset="1" stop-color="#1e1a55" /></linearGradient>
                <linearGradient id="h-pack" x1="0" y1="0" x2="1" y2="0"><stop offset="0" stop-color="#16152f" /><stop offset=".7" stop-color="#23214a" /><stop offset="1" stop-color="#3b3380" /></linearGradient>
                <filter id="h-blur" x="-20%" y="-20%" width="140%" height="140%"><feGaussianBlur stdDeviation="10" /></filter>
              </defs>

              <rect width="800" height="700" fill="url(#h-sky)" />
              <rect width="800" height="700" fill="url(#h-glow)" />
              <g fill="#fff">
                <circle cx="120" cy="140" r="1.3" opacity=".7" /><circle cx="220" cy="100" r="1" opacity=".5" />
                <circle cx="330" cy="160" r="1.2" opacity=".6" /><circle cx="700" cy="120" r="1.2" opacity=".7" />
                <circle cx="760" cy="200" r="1" opacity=".5" /><circle cx="430" cy="90" r="1" opacity=".5" />
                <circle cx="640" cy="60" r="1.3" opacity=".6" />
              </g>

              <g fill="url(#h-far)" opacity=".85">
                <rect x="90" y="330" width="46" height="370" /><rect x="140" y="290" width="40" height="410" />
                <rect x="186" y="350" width="52" height="350" /><rect x="360" y="300" width="44" height="400" />
                <rect x="408" y="250" width="38" height="450" /><rect x="452" y="320" width="50" height="380" />
                <rect x="640" y="270" width="48" height="430" /><rect x="694" y="320" width="54" height="380" />
                <rect x="752" y="240" width="48" height="460" />
              </g>
              <g fill="url(#h-mid)">
                <rect x="330" y="380" width="70" height="320" /><rect x="410" y="420" width="64" height="280" />
                <rect x="640" y="400" width="72" height="300" /><rect x="718" y="360" width="82" height="340" />
              </g>
              <g fill="#b9a4ff" opacity=".55">
                <rect x="342" y="400" width="7" height="10" /><rect x="360" y="400" width="7" height="10" />
                <rect x="378" y="424" width="7" height="10" /><rect x="342" y="448" width="7" height="10" />
                <rect x="422" y="440" width="7" height="10" /><rect x="440" y="464" width="7" height="10" />
                <rect x="456" y="440" width="7" height="10" /><rect x="654" y="420" width="7" height="10" />
                <rect x="672" y="444" width="7" height="10" /><rect x="690" y="420" width="7" height="10" />
                <rect x="732" y="384" width="7" height="10" /><rect x="752" y="408" width="7" height="10" />
                <rect x="772" y="384" width="7" height="10" /><rect x="752" y="440" width="7" height="10" />
                <rect x="150" y="310" width="6" height="9" /><rect x="164" y="336" width="6" height="9" />
                <rect x="418" y="270" width="6" height="9" /><rect x="760" y="262" width="6" height="9" />
              </g>

              <rect x="520" y="110" width="120" height="420" fill="#8b5cf6" opacity=".35" filter="url(#h-blur)" />
              <path d="M525 150 L585 110 L585 700 L525 700 Z" fill="url(#h-tower)" />
              <path d="M585 110 L635 145 L635 700 L585 700 Z" fill="#2a1872" />
              <path d="M525 150 L585 110 L635 145" stroke="#e4d9ff" stroke-width="2.5" fill="none" />
              <path d="M585 110 L585 700" stroke="#cbb8ff" stroke-width="1.2" opacity=".7" />
              <g font-family="Inter, system-ui, sans-serif" font-size="17" font-style="italic" font-weight="500" fill="#f1ebff">
                <text x="536" y="200">Watch</text><text x="536" y="226">Match</text>
                <text x="536" y="252">Apply</text><text x="536" y="278">Repeat</text>
              </g>

              <path d="M300 700 C 380 640, 520 640, 505 585 C 492 540, 590 530, 578 480 L 596 480 C 612 535, 520 548, 540 592 C 560 645, 470 670, 520 700 Z" fill="url(#h-road)" opacity=".35" filter="url(#h-blur)" />
              <path d="M300 700 C 380 640, 520 640, 505 585 C 492 540, 590 530, 578 480 L 596 480 C 612 535, 520 548, 540 592 C 560 645, 470 670, 520 700 Z" fill="url(#h-road)" opacity=".75" />
              <path d="M410 700 C 450 660, 530 650, 522 590 C 515 545, 590 530, 587 482" stroke="#f4efff" stroke-width="2" stroke-dasharray="10 12" fill="none" opacity=".7" />

              <path d="M0 610 C 120 580, 240 600, 330 640 L 330 700 L 0 700 Z" fill="#0b0920" />
              <path d="M600 660 C 680 620, 750 630, 800 610 L 800 700 L 600 700 Z" fill="#0b0920" />

              <g>
                <path d="M150 700 L 160 520 Q 168 470 222 458 L 300 458 Q 352 470 360 520 L 372 700 Z" fill="url(#h-jacket)" />
                <path d="M300 458 Q 352 470 360 520 L 372 700" stroke="#a98bff" stroke-width="3" fill="none" opacity=".55" />
                <rect x="244" y="430" width="34" height="34" rx="8" fill="#c98d72" />
                <ellipse cx="236" cy="410" rx="8" ry="12" fill="#c98d72" />
                <path d="M226 418 C 214 380, 228 346, 262 340 C 296 336, 316 360, 312 396 C 310 420, 300 438, 282 444 L 246 444 C 236 438, 230 430, 226 418 Z" fill="#141230" />
                <path d="M232 372 L 222 352 L 244 360 L 240 338 L 262 352 L 270 330 L 280 352 L 300 338 L 298 362 L 316 356 L 306 378 Z" fill="#141230" />
                <path d="M300 348 C 314 362, 316 388, 308 414" stroke="#b69cff" stroke-width="2.5" fill="none" opacity=".6" />
                <path d="M220 466 Q 250 476 262 470 Q 276 476 304 466" stroke="#2a2470" stroke-width="10" fill="none" stroke-linecap="round" />
                <rect x="196" y="488" width="134" height="200" rx="34" fill="url(#h-pack)" />
                <path d="M240 488 Q 263 468 286 488" stroke="#2b2958" stroke-width="7" fill="none" />
                <rect x="214" y="580" width="98" height="84" rx="20" fill="#1c1b3d" stroke="#34316a" stroke-width="2" />
                <path d="M226 600 L 300 600" stroke="#3d3a78" stroke-width="3" stroke-linecap="round" />
                <path d="M196 520 L 170 520" stroke="#15142d" stroke-width="14" stroke-linecap="round" />
                <path d="M330 520 L 352 524" stroke="#15142d" stroke-width="14" stroke-linecap="round" />
                <path d="M330 510 Q 334 580 328 660" stroke="#8f74ff" stroke-width="2" fill="none" opacity=".5" />
              </g>
            </svg>
          </div>

          <div class="art-caption" aria-hidden="true">
            <app-icon name="trend" />
            <b>A first look at new roles,<br />before the aggregators</b>
          </div>
        </div>

        <div class="hero-stats" id="facts">
          <div class="hstat">
            <span class="tile"><app-icon name="layers" /></span>
            <div><div class="v">5</div><div class="l">ATS platforms</div></div>
          </div>
          <div class="hstat">
            <span class="tile"><app-icon name="clock" /></span>
            <div><div class="v">30 min</div><div class="l">Between board checks</div></div>
          </div>
          <div class="hstat">
            <span class="tile"><app-icon name="sparkle" /></span>
            <div><div class="v">6 days</div><div class="l">Freshness window</div></div>
          </div>
          <div class="hstat">
            <span class="tile"><app-icon name="shield" /></span>
            <div><div class="v">0</div><div class="l">Skills ever invented</div></div>
          </div>
        </div>
      </div>
    </div>
  `,
})
export class AuthHero {
  protected readonly theme = inject(ThemeService);

  /** Which page hosts the hero — decides where Login / Get Started point. */
  readonly mode = input<'login' | 'register'>('login');

  protected readonly platforms = ['Greenhouse', 'Lever', 'Ashby', 'Workable', 'SmartRecruiters'];

  protected scrollTo(id: string): void {
    document.getElementById(id)?.scrollIntoView({ behavior: 'smooth', block: 'center' });
  }

  /** "Login" on the login page / "Get Started" on register: bring the form into view. */
  protected focusForm(): void {
    const email = document.getElementById('email') as HTMLInputElement | null;
    email?.scrollIntoView({ behavior: 'smooth', block: 'center' });
    email?.focus({ preventScroll: true });
  }
}
