package com.localcommerce.delivery.utils;

public class DistanceUtil {

    private static final int EARTH_RADIUS_KM = 6371;

    /**
     * Calculates the straight-line distance between two coordinates in kilometers.
     */
    public static double calculateDistance(double startLat, double startLng, double endLat, double endLng) {
        double dLat = Math.toRadians(endLat - startLat);
        double dLng = Math.toRadians(endLng - startLng);

        double startLatRad = Math.toRadians(startLat);
        double endLatRad = Math.toRadians(endLat);

        double a = Math.sin(dLat / 2) * Math.sin(dLat / 2) +
                Math.sin(dLng / 2) * Math.sin(dLng / 2) * Math.cos(startLatRad) * Math.cos(endLatRad);

        double c = 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a));

        return EARTH_RADIUS_KM * c;
    }
}