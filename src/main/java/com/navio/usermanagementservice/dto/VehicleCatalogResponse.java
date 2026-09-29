package com.navio.usermanagementservice.dto;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/** Published catalogue snapshot, retaining version and field-specific provenance. */
public record VehicleCatalogResponse(
        String id, String make, String model, String trim, Short year,
        String market, BigDecimal batteryCapacityKwh, String batteryCapacityBasis,
        BigDecimal rangeKm, String rangeStandard, List<String> connectorTypes,
        BigDecimal maxAcKw, BigDecimal maxDcKw, String imageUrl,
        String sourceUrl, LocalDate verifiedAt, Long version, VehicleEnergyProfile energyProfile
) {
    public VehicleCatalogResponse(String id, String make, String model, String trim, Short year,
            String market, BigDecimal batteryCapacityKwh, String batteryCapacityBasis,
            BigDecimal rangeKm, String rangeStandard, List<String> connectorTypes,
            BigDecimal maxAcKw, BigDecimal maxDcKw, String imageUrl,
            String sourceUrl, LocalDate verifiedAt, Long version) {
        this(id, make, model, trim, year, market, batteryCapacityKwh, batteryCapacityBasis,
                rangeKm, rangeStandard, connectorTypes, maxAcKw, maxDcKw, imageUrl, sourceUrl, verifiedAt, version, null);
    }
    public VehicleCatalogResponse {
        if (energyProfile == null) {
            var basis = switch (batteryCapacityBasis == null ? "UNKNOWN" : batteryCapacityBasis) {
                case "USABLE" -> VehicleEnergyProfile.CapacityBasis.USABLE;
                case "GROSS" -> VehicleEnergyProfile.CapacityBasis.GROSS;
                case "MANUFACTURER_DECLARED" -> VehicleEnergyProfile.CapacityBasis.MANUFACTURER_DECLARED_UNSPECIFIED;
                default -> VehicleEnergyProfile.CapacityBasis.UNKNOWN;
            };
            energyProfile = new VehicleEnergyProfile(1,
                    rangeKm != null && rangeKm.signum() > 0 ? VehicleEnergyProfile.ModelKind.RATED_RANGE : VehicleEnergyProfile.ModelKind.UNAVAILABLE,
                    VehicleEnergyProfile.SelectionMode.CATALOG_DEFAULT, null,
                    VehicleEnergyProfile.ConsumptionSource.UNKNOWN, VehicleEnergyProfile.MeasurementBasis.UNKNOWN,
                    VehicleEnergyProfile.Standard.NONE, null,
                    basis == VehicleEnergyProfile.CapacityBasis.USABLE ? batteryCapacityKwh : null, basis,
                    rangeKm, VehicleEnergyProfile.rangeStandard(rangeStandard));
        }
    }
}
