// Hook for checking order numbers against the ERP/WMS (phase 3).
// Phase 1 ships the no-op implementation: order numbers are only recorded.

export interface OrderCheckInput {
  entityCode: number;
  orderNo: string | null;
  supplier: string | null;
}

export type OrderCheckResult = { ok: true } | { ok: false; reason: string };

export interface OrderValidator {
  check(input: OrderCheckInput): Promise<OrderCheckResult>;
}

export const noopOrderValidator: OrderValidator = {
  async check() {
    return { ok: true };
  },
};
