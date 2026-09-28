package com.navio.usermanagementservice.dto;

import java.math.BigDecimal;

/** Selection/provenance contract; route calculation is a separate concern. */
public record VehicleEnergyProfile(
        int version, ModelKind modelKind, SelectionMode selectionMode,
        BigDecimal consumptionKwhPer100km, ConsumptionSource consumptionSource,
        MeasurementBasis consumptionMeasurementBasis, Standard consumptionStandard,
        String sourceUrl, BigDecimal usableBatteryCapacityKwh, CapacityBasis capacityBasis,
        BigDecimal ratedRangeKm, Standard ratedRangeStandard
) {
    public enum ModelKind { CONSUMPTION, RATED_RANGE, UNAVAILABLE }
    public enum SelectionMode { CATALOG_DEFAULT, USER_OVERRIDE, LEGACY_UNCONFIRMED }
    public enum ConsumptionSource { USER_OBSERVED, MANUFACTURER_REPORTED, REGULATORY_REPORTED, UNKNOWN }
    public enum MeasurementBasis { BATTERY_SIDE, WALL_SIDE, TRIP_COMPUTER, UNKNOWN }
    public enum Standard { NEDC, WLTP, EPA, CLTC, OTHER, NONE }
    public enum CapacityBasis { USABLE, GROSS, MANUFACTURER_DECLARED_UNSPECIFIED, UNKNOWN }

    public static Standard rangeStandard(String standard) {
        if (standard == null) return Standard.NONE;
        try { return Standard.valueOf(standard); }
        catch (IllegalArgumentException exception) { return Standard.OTHER; }
    }

    public static VehicleEnergyProfile unspecified(BigDecimal consumption, BigDecimal range,
                                                   String rangeStandard, boolean catalogDefault,
                                                   boolean declaredCapacity) {
        return new VehicleEnergyProfile(1,
                consumption != null ? ModelKind.CONSUMPTION : range != null ? ModelKind.RATED_RANGE : ModelKind.UNAVAILABLE,
                catalogDefault ? SelectionMode.CATALOG_DEFAULT : SelectionMode.LEGACY_UNCONFIRMED,
                consumption, ConsumptionSource.UNKNOWN, MeasurementBasis.UNKNOWN, Standard.NONE,
                null, null, declaredCapacity ? CapacityBasis.MANUFACTURER_DECLARED_UNSPECIFIED : CapacityBasis.UNKNOWN,
                range, rangeStandard(rangeStandard));
    }

    public VehicleEnergyProfile observed(MeasurementBasis basis) {
        return new VehicleEnergyProfile(1, ModelKind.CONSUMPTION, SelectionMode.USER_OVERRIDE,
                consumptionKwhPer100km, ConsumptionSource.USER_OBSERVED, basis, Standard.NONE,
                null, usableBatteryCapacityKwh, capacityBasis, ratedRangeKm, ratedRangeStandard);
    }

    /** Only explicitly evidenced battery-side catalogue consumption is a default. */
    public static VehicleEnergyProfile defaultFor(VehicleCatalogResponse catalog, BigDecimal customRange) {
        return defaultFor(catalog, customRange, false);
    }

    public static VehicleEnergyProfile defaultFor(VehicleCatalogResponse catalog, BigDecimal customRange, boolean ratedRangeOnly) {
        var profile = catalog == null ? null : catalog.energyProfile();
        boolean suitable = !ratedRangeOnly && profile != null && profile.modelKind() == ModelKind.CONSUMPTION
                && profile.consumptionKwhPer100km() != null && profile.consumptionKwhPer100km().signum() > 0
                && (profile.consumptionSource() == ConsumptionSource.MANUFACTURER_REPORTED
                    || profile.consumptionSource() == ConsumptionSource.REGULATORY_REPORTED)
                && profile.consumptionMeasurementBasis() == MeasurementBasis.BATTERY_SIDE
                && profile.sourceUrl() != null && !profile.sourceUrl().isBlank();
        var range = catalog == null ? customRange : catalog.rangeKm();
        return new VehicleEnergyProfile(1,
                suitable ? ModelKind.CONSUMPTION : range != null && range.signum() > 0 ? ModelKind.RATED_RANGE : ModelKind.UNAVAILABLE,
                SelectionMode.CATALOG_DEFAULT,
                suitable ? profile.consumptionKwhPer100km() : null,
                suitable ? profile.consumptionSource() : ConsumptionSource.UNKNOWN,
                suitable ? profile.consumptionMeasurementBasis() : MeasurementBasis.UNKNOWN,
                suitable ? profile.consumptionStandard() : Standard.NONE,
                suitable ? profile.sourceUrl() : null,
                profile == null ? null : profile.usableBatteryCapacityKwh(),
                profile == null ? CapacityBasis.UNKNOWN : profile.capacityBasis(),
                range, catalog == null ? Standard.NONE : rangeStandard(catalog.rangeStandard()));
    }
}
