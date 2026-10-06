package com.localcommerce.delivery.utils;

import com.localcommerce.delivery.model.ProductUnit;
import org.junit.jupiter.api.Test;
import java.math.BigDecimal;
import static org.junit.jupiter.api.Assertions.*;

public class UnitConverterTest {

    @Test
    public void testSameUnitConversion() {
        BigDecimal amount = new BigDecimal("10.5");
        BigDecimal result = UnitConverter.convert(amount, ProductUnit.KG, ProductUnit.KG);
        assertEquals(amount, result);
    }

    @Test
    public void testGramsToKilograms() {
        BigDecimal amount = new BigDecimal("500");
        BigDecimal result = UnitConverter.convert(amount, ProductUnit.G, ProductUnit.KG);
        assertEquals(new BigDecimal("0.500"), result);
    }

    @Test
    public void testKilogramsToGrams() {
        BigDecimal amount = new BigDecimal("1.25");
        BigDecimal result = UnitConverter.convert(amount, ProductUnit.KG, ProductUnit.G);
        assertEquals(new BigDecimal("1250.000"), result);
    }

    @Test
    public void testMillilitersToLiters() {
        BigDecimal amount = new BigDecimal("750");
        BigDecimal result = UnitConverter.convert(amount, ProductUnit.ML, ProductUnit.L);
        assertEquals(new BigDecimal("0.750"), result);
    }

    @Test
    public void testLitersToMilliliters() {
        BigDecimal amount = new BigDecimal("2.5");
        BigDecimal result = UnitConverter.convert(amount, ProductUnit.L, ProductUnit.ML);
        assertEquals(new BigDecimal("2500.000"), result);
    }

    @Test
    public void testIncompatibleConversionThrowsException() {
        BigDecimal amount = new BigDecimal("1");
        assertThrows(IllegalArgumentException.class, () -> {
            UnitConverter.convert(amount, ProductUnit.KG, ProductUnit.L);
        });
    }
}
