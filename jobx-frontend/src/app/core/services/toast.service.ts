import { Injectable, signal } from '@angular/core';

export type ToastKind = 'info' | 'ok' | 'err';

export interface Toast {
  id: number;
  message: string;
  kind: ToastKind;
  /** Present only on optimistic status changes — the mockup's undo affordance. */
  undo?: () => void;
}

const PLAIN_MS = 3200;
const UNDO_MS = 6000;

@Injectable({ providedIn: 'root' })
export class ToastService {
  private readonly items = signal<Toast[]>([]);
  private readonly timers = new Map<number, ReturnType<typeof setTimeout>>();
  private nextId = 1;

  readonly toasts = this.items.asReadonly();

  show(message: string, options: { kind?: ToastKind; undo?: () => void } = {}): number {
    const id = this.nextId++;
    const toast: Toast = { id, message, kind: options.kind ?? 'info', undo: options.undo };
    this.items.update((list) => [...list, toast]);
    this.timers.set(
      id,
      setTimeout(() => this.dismiss(id), options.undo ? UNDO_MS : PLAIN_MS),
    );
    return id;
  }

  ok(message: string): number {
    return this.show(message, { kind: 'ok' });
  }

  error(message: string): number {
    return this.show(message, { kind: 'err' });
  }

  dismiss(id: number): void {
    const timer = this.timers.get(id);
    if (timer) {
      clearTimeout(timer);
      this.timers.delete(id);
    }
    this.items.update((list) => list.filter((t) => t.id !== id));
  }

  runUndo(toast: Toast): void {
    toast.undo?.();
    this.dismiss(toast.id);
  }
}
