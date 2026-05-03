import java.sql.*;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Instant;
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

            // 3. Günstigste zusammenhängende Slots berechnen und speichern
            List<String> cheapestSlot = findCheapestContiguousSlot(futurePrices, 10);
            if (!cheapestSlot.isEmpty()) {
                storeCheapestSlotInMariaDB(cheapestSlot, 10);
            } else {
                System.out.println("Keine gültigen Slots gefunden.");
            }

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

        // JSON parsen (vereinfacht, für echte Anwendung: org.json oder Gson verwenden)
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

    // 3. Günstigste zusammenhängende Slots finden (Sliding Window)
    private static List<String> findCheapestContiguousSlot(Map<String, Double> prices, int slotLength) {
        // 1. Nach Zeit sortieren (wichtig!)
        List<Map.Entry<String, Double>> sortedEntries = new ArrayList<>(prices.entrySet());
        sortedEntries.sort(Comparator.comparing(Map.Entry::getKey));

        // 2. Gleitendes Fenster anwenden
        double minAvgPrice = Double.MAX_VALUE;
        List<String> bestSlot = new ArrayList<>();

        for (int i = 0; i <= sortedEntries.size() - slotLength; i++) {
            List<Map.Entry<String, Double>> slot = sortedEntries.subList(i, i + slotLength);
            double avgPrice = slot.stream().mapToDouble(Map.Entry::getValue).average().orElse(0.0);

            if (avgPrice < minAvgPrice) {
                minAvgPrice = avgPrice;
                bestSlot = slot.stream().map(Map.Entry::getKey).toList();
            }
        }
        return bestSlot;
    }

    // 4. Günstigste Slots in MariaDB speichern
    private static void storeCheapestSlotInMariaDB(List<String> slotTimestamps, int slotLength) throws SQLException {
        if (slotTimestamps.isEmpty()) return;

        String host = System.getenv("MARIADB_HOST");
        String port = System.getenv("MARIADB_PORT");
        String user = System.getenv("MARIADB_USER");
        String password = System.getenv("MARIADB_PASSWORD");
        String database = System.getenv("MARIADB_DATABASE");

        String jdbcUrl = "jdbc:mariadb://" + host + ":" + port + "/" + database;
        String startTime = slotTimestamps.get(0);
        String endTime = slotTimestamps.get(slotTimestamps.size() - 1);
        double avgPrice = slotTimestamps.stream()
                .mapToDouble(t -> prices.get(t))  // Annahme: prices ist eine Class-Variable (vereinfacht)
                .average()
                .orElse(0.0);

        try (Connection conn = DriverManager.getConnection(jdbcUrl, user, password)) {
            String sql = "INSERT INTO cheapest_slots (start_time, end_time, avg_price, slot_length) " +
                         "VALUES (?, ?, ?, ?) ON DUPLICATE KEY UPDATE avg_price = ?, end_time = ?";
            PreparedStatement stmt = conn.prepareStatement(sql);
            stmt.setString(1, startTime);
            stmt.setString(2, endTime);
            stmt.setDouble(3, avgPrice);
            stmt.setInt(4, slotLength);
            stmt.setDouble(5, avgPrice);
            stmt.setString(6, endTime);
            stmt.executeUpdate();
            System.out.println("Günstigster Slot gespeichert: " + startTime + " bis " + endTime +
                               " (Durchschnitt: " + avgPrice + " €/kWh)");
        }
    }
}
