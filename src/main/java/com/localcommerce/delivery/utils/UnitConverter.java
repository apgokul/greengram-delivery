package com.localcommerce.delivery.utils;

import com.localcommerce.delivery.model.ProductUnit;
import java.math.BigDecimal;
import java.math.RoundingMode;

public class UnitConverter {

    /**
     * Converts a quantity from one ProductUnit to another.
     * Supports basic metric conversions used in the system:
     * G <-> KG
     * ML <-> L
     *
     * @param amount The quantity to convert
     * @param fromUnit The unit of the provided quantity
     * @param toUnit The unit to convert to
     * @return The converted quantity in the requested unit
     * @throws IllegalArgumentException if the conversion is not supported or incompatible
     */
    public static BigDecimal convert(BigDecimal amount, ProductUnit fromUnit, ProductUnit toUnit) {
        if (amount == null || fromUnit == null || toUnit == null) {
            throw new IllegalArgumentException("Amount and units must not be null");
        }

        if (fromUnit == toUnit) {
            return amount;
        }

        // Weight conversions
        if (fromUnit == ProductUnit.G && toUnit == ProductUnit.KG) {
            return amount.divide(BigDecimal.valueOf(1000), 3, RoundingMode.HALF_UP);
        }
        if (fromUnit == ProductUnit.KG && toUnit == ProductUnit.G) {
            return amount.multiply(BigDecimal.valueOf(1000)).setScale(3, RoundingMode.HALF_UP);
        }

        // Volume conversions
        if (fromUnit == ProductUnit.ML && toUnit == ProductUnit.L) {
            return amount.divide(BigDecimal.valueOf(1000), 3, RoundingMode.HALF_UP);
        }
        if (fromUnit == ProductUnit.L && toUnit == ProductUnit.ML) {
            return amount.multiply(BigDecimal.valueOf(1000)).setScale(3, RoundingMode.HALF_UP);
        }

        throw new IllegalArgumentException(
                String.format("Unsupported or incompatible unit conversion from %s to %s", fromUnit, toUnit)
        );
    }
}
