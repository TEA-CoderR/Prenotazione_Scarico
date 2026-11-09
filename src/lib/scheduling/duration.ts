import type { VehicleType } from "@/db/schema";

export interface SlotRule {
  vehicleType: VehicleType;
  maxPallets: number;
  /** null = parcel count not limited for this vehicle type */
  maxParcels: number | null;
  durationMin: number;
}

/** Defaults agreed in the design doc. Sites can override them in `slot_rule`. */
export const DEFAULT_SLOT_RULES: SlotRule[] = [
  { vehicleType: "parcel", maxPallets: 0, maxParcels: 20, durationMin: 15 },
  { vehicleType: "van", maxPallets: 6, maxParcels: null, durationMin: 30 },
  { vehicleType: "truck", maxPallets: 18, maxParcels: null, durationMin: 60 },
  { vehicleType: "semi", maxPallets: 33, maxParcels: null, durationMin: 90 },
];

export class LoadNotAllowedError extends Error {
  constructor(message: string) {
    super(message);
    this.name = "LoadNotAllowedError";
  }
}

/**
 * How many minutes the dock is occupied. Site-specific rules win over defaults.
 * Throws when the load does not fit the chosen vehicle type.
 */
export function resolveDuration(
  rules: SlotRule[],
  vehicleType: VehicleType,
  pallets: number,
  parcels: number,
): number {
  const rule = rules.find((r) => r.vehicleType === vehicleType);
  if (!rule) throw new LoadNotAllowedError(`No slot rule for vehicle type "${vehicleType}"`);
  if (pallets < 0 || parcels < 0) throw new LoadNotAllowedError("Load cannot be negative");
  if (pallets === 0 && parcels === 0) throw new LoadNotAllowedError("Declare at least one pallet or parcel");
  if (pallets > rule.maxPallets) {
    throw new LoadNotAllowedError(`${vehicleType} allows at most ${rule.maxPallets} pallets`);
  }
  if (rule.maxParcels !== null && parcels > rule.maxParcels) {
    throw new LoadNotAllowedError(`${vehicleType} allows at most ${rule.maxParcels} parcels`);
  }
  return rule.durationMin;
}
