import { InjectionToken } from '@angular/core';

/**
 * Backend origin. The backend's CORS allow-list defaults to http://localhost:4200
 * (application.yml `jobx.cors.allowed-origins`) — a CORS failure means the origin
 * changed, not that CORS is missing (uiux_plan.md §11).
 */
export const API_BASE_URL = new InjectionToken<string>('API_BASE_URL', {
  providedIn: 'root',
  factory: () => 'http://localhost:8080',
});
