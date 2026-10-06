import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;

public class DbInspector {
    public static void main(String[] args) {
        String url = "jdbc:postgresql://ep-delicate-hat-azozpzyl.c-3.ap-southeast-1.aws.neon.tech/neondb?sslmode=require";
        String user = "neondb_owner";
        String password = "npg_T0mcEMuyOn4h";

        try (Connection conn = DriverManager.getConnection(url, user, password);
             Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery("SELECT sku, name, current_price, stock_quantity, quantity, unit, is_active FROM products")) {
             
            System.out.println("SKU | Name | Price | Stock Qty | Pkg Qty | Unit | Active");
            System.out.println("---------------------------------------------------------");
            while (rs.next()) {
                System.out.printf("%s | %s | %s | %d | %s | %s | %b%n",
                        rs.getString("sku"),
                        rs.getString("name"),
                        rs.getBigDecimal("current_price"),
                        rs.getInt("stock_quantity"),
                        rs.getBigDecimal("quantity"),
                        rs.getString("unit"),
                        rs.getBoolean("is_active")
                );
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
    }
}
