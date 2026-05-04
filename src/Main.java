import java.sql.*;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.*;

public class Main {
    public static void main(String[] args) {
        try {
            System.out.println("HEMS Add-on gestartet. Lade Strompreise...");

            // 1. Zukunftswerte aus Home Assistant abrufen
            Map<String, Double> futurePrices = fetchPricesFromHA();
            System.out.println("Geladene Zukunftswerte: " + futurePrices.size());

            // 2. Zukunftswerte in MariaDB speichern
            storePricesInMariaDB(futurePrices);

            System.out.println("HEMS Add-on erfolgreich ausgeführt.");

        } catch (Exception e) {
            System.err.println("Fehler im HEMS Add-on: " + e.getMessage());
            e.printStackTrace();
        }
    }

    // 1. Daten aus Home Assistant abrufen
    private static Map<String, Double> fetchPricesFromHA() throws Exception {
        String haUrl = "http://supervisor/core/api/states/sensor.ostrom_energy_spotpreis";
        String haToken = System.getenv("HA_TOKEN");

        HttpClient client = HttpClient.newHttpClient();
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(haUrl))
                .header("Authorization", "Bearer " + haToken)
                .header("Content-Type", "application/json")
                .build();

        HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() != 200) {
            throw new Exception("Fehler beim Abrufen der HA-Daten. Statuscode: " + response.statusCode());
        }

        String jsonResponse = response.body();
        Map<String, Double> prices = new HashMap<>();

        // JSON parsen (vereinfacht)
        if (jsonResponse.contains("\"prices\":{")) {
            String pricesJson = jsonResponse.split("\"prices\":\\{")[1].split("\\}")[0];
            String[] entries = pricesJson.split(",");
            for (String entry : entries) {
                String[] keyValue = entry.split(":");
                String timestamp = keyValue[0].trim().replace("\"", "");
                double price = Double.parseDouble(keyValue[1].trim());
                prices.put(timestamp, price);
            }
        }
        return prices;
    }

    // 2. Zukunftswerte in MariaDB speichern
    private static void storePricesInMariaDB(Map<String, Double> prices) throws SQLException {
        String host = System.getenv("MARIADB_HOST");
        String port = System.getenv("MARIADB_PORT");
        String user = System.getenv("MARIADB_USER");
        String password = System.getenv("MARIADB_PASSWORD");
        String database = System.getenv("MARIADB_DATABASE");

        String jdbcUrl = "jdbc:mariadb://" + host + ":" + port + "/" + database;

        try (Connection conn = DriverManager.getConnection(jdbcUrl, user, password)) {
            String sql = "INSERT INTO future_energy_prices (timestamp, price, sensor_id) " +
                         "VALUES (?, ?, ?) ON DUPLICATE KEY UPDATE price = ?";
            PreparedStatement stmt = conn.prepareStatement(sql);

            for (Map.Entry<String, Double> entry : prices.entrySet()) {
                stmt.setString(1, entry.getKey());
                stmt.setDouble(2, entry.getValue());
                stmt.setString(3, "sensor.ostrom_energy_spotpreis");
                stmt.setDouble(4, entry.getValue());
                stmt.addBatch();
            }
            stmt.executeBatch();
            System.out.println("Erfolgreich " + prices.size() + " Zukunftswerte in MariaDB gespeichert.");
        }
    }
}
